module.exports = (function () {

    var tls = require('tls');
    var mockserver = require('..');

    /**
     * Wait until the server can complete a TLS handshake on the given port.
     *
     * start_mockserver only proves the HTTP control plane is answering - it polls
     * "PUT /mockserver/retrieve" over plain HTTP. When MockServer is started with
     * dynamicallyCreateCertificateAuthorityCertificate=true it still has to generate
     * a CA key pair and a leaf certificate before it can serve TLS on that same
     * (port-unified) port. A HTTPS request issued in that window is closed mid
     * handshake, surfacing as ECONNRESET "Client network socket disconnected before
     * secure TLS connection was established".
     *
     * Waiting for an actual handshake to succeed gates the test on the condition it
     * really depends on, rather than retrying the assertions themselves.
     *
     * The question being asked is "has the server got as far as serving TLS", not
     * "do we trust its certificate". A freshly generated dynamic CA is untrusted by
     * design, so we disable certificate validation on this probe (rejectUnauthorized:
     * false) and treat a completed handshake (secureConnect) as the readiness signal.
     *
     * This replaces an earlier, subtly-broken design that relied on a peer certificate
     * being attached to a certificate-verification error. On Node 20/22 that branch is
     * dead: for a self-signed cert error.cert is undefined and getPeerCertificate()
     * returns {}, so the probe only ever succeeded via secureConnect - which in turn
     * only fired because an unrelated test set NODE_TLS_REJECT_UNAUTHORIZED=0 process
     * wide as a side effect. Readiness therefore silently depended on test ordering and
     * a global env mutation. Disabling validation on the probe itself removes both.
     */
    return function (host, port, timeoutMs) {
        // Generous on purpose. Waiting costs nothing when the server is healthy - a ready server
        // completes the handshake on the first attempt in milliseconds - so the only thing this
        // number decides is how much CI contention is tolerated before a slow start is called a
        // failure. 30s was too tight: it went green five builds running and then failed on a loaded
        // agent, which is the same flake in a new disguise rather than a real fault.
        var limit = timeoutMs || 120000;
        var start = Date.now();
        var deadline = start + limit;
        var attempts = 0;

        return new Promise(function (resolve, reject) {
            function attempt() {
                var settled = false;
                attempts++;
                var socket = tls.connect({
                    host: host,
                    port: port,
                    // untrusted-by-design dynamic CA: a completed handshake is the readiness signal
                    rejectUnauthorized: false
                });

                // a handshake that stalls must not hold the whole wait open
                socket.setTimeout(2000);

                function succeed() {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.destroy();
                    var elapsed = Date.now() - start;
                    // Surface a slow start rather than hiding it in a passing test: the failure mode
                    // this guard exists for is readiness creeping towards the limit, and a green run
                    // that took 40s is the warning that the next one will not be.
                    if (elapsed > 5000) {
                        console.error('# TLS on port ' + port + ' took ' + elapsed + 'ms (' + attempts + ' attempts) to become ready');
                    }
                    resolve();
                }

                function retry(error) {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.destroy();
                    if (Date.now() >= deadline) {
                        // Report the attempt count as well - "one attempt that hung" and "hundreds
                        // that were refused" are different faults and the message should say which -
                        // plus whether the java process is even still alive and what it last printed,
                        // so a permanently-broken TLS server (e.g. a torn dynamic-CA pair) is diagnosable
                        // from the failure itself rather than needing a re-run under a debugger.
                        reject(new Error('MockServer did not serve TLS on port ' + port +
                            ' within ' + limit + 'ms (' + attempts + ' attempts over ' +
                            (Date.now() - start) + 'ms): ' + error.message + '\n' + serverDiagnostics()));
                    } else {
                        setTimeout(attempt, 100);
                    }
                }

                // a completed handshake proves the server is serving TLS
                socket.once('secureConnect', succeed);

                socket.once('error', retry);

                socket.once('timeout', function () {
                    retry(new Error('TLS handshake timed out'));
                });
            }

            attempt();
        });
    };

    /**
     * Best-effort description of the launched MockServer's state for a readiness-timeout message:
     * whether the java process is still alive (and its exit status if not) and the tail of its output.
     */
    function serverDiagnostics() {
        var lines = [];
        try {
            var exit = mockserver.getMockServerExit && mockserver.getMockServerExit();
            var proc = mockserver.getMockServerProcess && mockserver.getMockServerProcess();
            if (exit) {
                lines.push('java process has exited (code=' + exit.code + ', signal=' + exit.signal + ')');
            } else if (proc && proc.pid && proc.exitCode === null) {
                lines.push('java process (pid ' + proc.pid + ') is still running but is not serving TLS');
            } else {
                lines.push('java process state is unknown');
            }
            var output = mockserver.getMockServerOutput && mockserver.getMockServerOutput(30);
            if (output) {
                lines.push('last MockServer output:\n' + output);
            } else {
                lines.push('no MockServer output was captured');
            }
        } catch (e) {
            lines.push('unable to collect server diagnostics: ' + e.message);
        }
        return lines.join('\n');
    }
})();
