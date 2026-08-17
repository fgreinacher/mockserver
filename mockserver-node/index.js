/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

module.exports = (function () {

    var mockServer;
    // Bounded ring buffer of the launched MockServer's stdout+stderr, and its exit status once it dies.
    // Non-verbose starts used to route MockServer stdout to 'ignore', discarding the exact
    // "SEVERE ... certificate does not verify with supplied key" line that was needed to diagnose a
    // dynamic-CA generation race. We now always capture the output (bounded, so a long-running server
    // cannot grow it without limit) so a failed startup / readiness check can surface the tail.
    var mockServerOutput = '';
    var mockServerExit;
    // Measured in JS string length (UTF-16 code units), not bytes: String.slice works in code units, and
    // this is only a diagnostic-tail bound, not an exact byte budget. The cap is approximate at the edges
    // (a multibyte UTF-8 sequence straddling a chunk boundary can render as one replacement character in
    // the tail) but the buffer is bounded either way, which is all this needs to guarantee.
    var MAX_CAPTURED_OUTPUT_CHARS = 65536;

    function appendCapturedOutput(chunk) {
        mockServerOutput += chunk.toString();
        if (mockServerOutput.length > MAX_CAPTURED_OUTPUT_CHARS) {
            mockServerOutput = mockServerOutput.slice(mockServerOutput.length - MAX_CAPTURED_OUTPUT_CHARS);
        }
    }

    function capturedOutputTail(maxLines) {
        var lines = mockServerOutput.split(/\r?\n/).filter(function (line) {
            return line.length > 0;
        });
        if (maxLines && lines.length > maxLines) {
            lines = lines.slice(lines.length - maxLines);
        }
        return lines.join('\n');
    }

    // KNOWN LIMITATION: logLevel, artifactoryHost, artifactoryPath and
    // mockServerVersion below are all module-level, and start_mockserver
    // overwrites each of them permanently from its options rather than treating
    // them as per-call values. Two concurrent starts in one process therefore
    // share one version, one repository and one log level - the second can
    // launch the first's jar - and any later call that omits an option silently
    // inherits the last explicit one. This is the in-process form of the
    // cross-process race that resolveJarPath and downloadJar fix; fixing it
    // means threading all four through as locals, which changes behaviour
    // callers may be relying on today.
    var logLevel;
    var artifactoryHost = 'repo1.maven.org';
    var artifactoryPath = '/maven2/org/mock-server/mockserver-netty/';
    var mockServerVersion = require('./package.json').version;
    var Q = require('q');
    var http = require('http');
    var fs = require('fs');
    var path = require('path');

    /**
     * Resolve the MockServer jar for a specific version to an absolute path.
     *
     * The jar is looked for in a fixed set of known directories rather than with
     * a recursive wildcard. A recursive glob matches any copy anywhere beneath
     * the working directory, so it can pick a copy belonging to something else
     * entirely, and the path it returns is not guaranteed to still exist by the
     * time java is asked to open it. Every candidate below is checked for
     * existence, and a missing jar is reported rather than handed to java as an
     * undefined argument.
     *
     * Candidates, in priority order:
     *   1. this package's own directory - where downloadJar stores the jar
     *   2. node_modules/mockserver-node below the working directory - an install
     *      that this module was not itself loaded from
     *   3. the working directory - where releases before 7.4.1 wrote the jar
     *
     * @param {string} jarName file name of the jar for the version being launched
     * @returns {string} absolute path to an existing jar
     * @throws {Error} if no candidate directory holds the jar
     */
    function resolveJarPath(jarName) {
      var candidates = [
        path.join(__dirname, jarName),
        path.join(process.cwd(), 'node_modules', 'mockserver-node', jarName),
        path.join(process.cwd(), jarName)
      ];
      for (var i = 0; i < candidates.length; i++) {
        if (fs.existsSync(candidates[i])) {
          return candidates[i];
        }
      }
      throw new Error('Unable to find ' + jarName + ', looked in: ' + candidates.join(', '));
    }

    /**
     * Resolve an explicitly-configured jar (the jarPath option or the
     * MOCKSERVER_JAR_PATH environment variable) to an absolute path, failing
     * loudly if nothing usable is there.
     *
     * Unlike resolveJarPath / downloadJar, this deliberately does NOT fall back
     * to a downloaded release: a caller that named a specific jar wants THAT jar,
     * and silently downloading a published version instead would mask a
     * missing/mis-built artifact - in CI, the very failure this exists to catch
     * (a build step that should have produced the jar but did not). A missing
     * path, or one that is not a regular file, is therefore a hard error.
     *
     * @param {string} configuredPath the path as configured
     * @param {string} source human-readable origin, used in the log/error message
     * @param {boolean} log whether to log the resolved path
     * @returns {string} absolute path to the existing jar file
     * @throws {Error} if the path does not resolve to an existing regular file
     */
    function resolveExplicitJarPath(configuredPath, source, log) {
      var absolute = path.resolve(configuredPath);
      var stats;
      try {
        stats = fs.statSync(absolute);
      } catch (missing) {
        throw new Error('The MockServer jar configured via ' + source + ' ("' + configuredPath +
          '") does not exist at ' + absolute + ' - refusing to fall back to downloading a release');
      }
      if (!stats.isFile()) {
        throw new Error('The MockServer jar configured via ' + source + ' ("' + configuredPath +
          '") is not a regular file at ' + absolute + ' - refusing to fall back to downloading a release');
      }
      if (log) {
        console.log('Using MockServer jar from ' + source + ': ' + absolute);
      }
      return absolute;
    }

    function defer() {
      var promise = (global.protractor && protractor.promise.USE_PROMISE_MANAGER !== false)
        ? protractor.promise
        : Q;
      var deferred = promise.defer();
  
      if (deferred.fulfill && !deferred.resolve) {
        deferred.resolve = deferred.fulfill;
      }
      return deferred;
    }
  
    function checkStarted(request, retries, promise, verbose) {
      var deferred = promise || defer();
  
      var req = http.request(request);
      req.setTimeout(2000);
  
      req.once('response', function (response) {
        var body = '';
  
        response.on('data', function (chunk) {
          body += chunk;
        });
  
        response.on('end', function () {
          deferred.resolve({
            statusCode: response.statusCode,
            body: body
          });
        });
      });
  
      req.once('error', function (error) {
        if (retries > 0) {
          setTimeout(function () {
            if (verbose) {
              console.log("waiting for MockServer to start retries remaining: " + retries);
            }
            checkStarted(request, retries - 1, promise, verbose);
          }, 100);
        } else {
          if (verbose) {
            console.log("MockServer failed to start");
          }
          // Surface the diagnostic evidence that non-verbose starts used to discard: whether the java
          // process is still alive (and its exit status if not) and the tail of MockServer's own output.
          if (mockServerExit) {
            console.error("MockServer java process exited before becoming ready (code=" +
              mockServerExit.code + ", signal=" + mockServerExit.signal + ")");
          } else if (mockServer && mockServer.pid) {
            console.error("MockServer java process (pid " + mockServer.pid +
              ") is still running but did not become ready");
          }
          var tail = capturedOutputTail(30);
          if (tail) {
            console.error("last MockServer output:\n" + tail);
          }
          deferred.reject(error);
        }
      });
  
      req.end();
  
      return deferred.promise;
    }
  
    function checkStopped(request, retries, promise, verbose) {
      var deferred = promise || defer();
  
      var req = http.request(request);
  
      req.once('response', function (response) {
        var body = '';
  
        response.on('data', function (chunk) {
          body += chunk;
        });
  
        response.on('end', function () {
          if (retries > 0) {
            if (verbose) {
              console.log("waiting for MockServer to stop retries remaining: " + retries);
            }
            setTimeout(function () {
              checkStopped(request, retries - 1, promise, verbose);
            }, 100);
          } else {
            if (verbose) {
              console.log("MockServer failed to stop");
            }
            deferred.reject();
          }
        });
      });
  
      req.once('error', function () {
        deferred.resolve();
      });
  
      req.end();
  
      return deferred.promise;
    }
  
    function sendRequest(request) {
      var deferred = defer();
  
      var callback = function (response) {
        var body = '';
  
        if (response.statusCode === 400 || response.statusCode === 404) {
          deferred.reject(response.statusCode);
        }
  
        response.on('data', function (chunk) {
          body += chunk;
        });
  
        response.on('end', function () {
          deferred.resolve({
            statusCode: response.statusCode,
            headers: response.headers,
            body: body
          });
        });
      };
  
      var req = http.request(request, callback);
  
      req.once('error', function (err) {
        deferred.reject(err);
      });
  
      req.end();
  
      return deferred.promise;
    }
  
    function stop_mockserver(options) {
      var port;
      var deferred = defer();
  
      if (options && options.serverPort) {
        if (options.serverPort) {
          port = port || options.serverPort;
        }
        if (options.verbose) {
          console.log('Using port \'' + port + '\' to stop MockServer and MockServer Proxy');
        }
        sendRequest({
          method: 'PUT',
          host: "localhost",
          path: "/stop",
          port: port
        }).then(
          function () {
            if (mockServer) {
              mockServer.kill();
            }
            checkStopped({
              method: 'PUT',
              host: "localhost",
              path: "/reset",
              port: port
            }, 100, deferred, options && options.verbose); // wait for 10 seconds
          },
          function (err) {
            if ((err && err.code === "ECONNREFUSED") || err === 404) {
              try {
                if (mockServer) {
                  mockServer.kill();
                }
              } catch (e) {
              }
              deferred.resolve();
            } else {
              deferred.reject(err);
            }
          }
        );
  
      } else {
        deferred.reject("Please specify \"serverPort\", for example: \"stop_mockserver({ serverPort: 1080 })\"");
      }
      return deferred.promise;
    }
  
    function start_mockserver(options) {
      var port;
      var deferred = defer();
  
      if (!(options && options.serverPort)) {
        deferred.reject('Please specify "serverPort", for example: "start_mockserver({ serverPort: 1080 })"');
        return deferred.promise;
      }
  
      if ((options.systemProperties)) {
        deferred.reject('The option "systemProperties" was renamed to "jvmOptions" in 5.4.1. Please migrate to the new option name');
        return deferred.promise;
      }
  
      if (options.artifactoryHost) {
        artifactoryHost = options.artifactoryHost;
      }
  
      if (options.artifactoryPath) {
        artifactoryPath = options.artifactoryPath;
      }
  
      if (options.mockServerVersion) {
        mockServerVersion = options.mockServerVersion;
      }
  
      if (options.trace) {
        logLevel = 'TRACE';
        options.verbose = true;
      } else if (options.verbose) {
        logLevel = 'DEBUG';
      }
  
      var startupRetries = options.startupRetries || options.javaDebugPort ? 500 : 110;

      // An explicitly-provided jar (the jarPath option or the MOCKSERVER_JAR_PATH
      // environment variable) is used as-is and short-circuits the download
      // entirely. This is how an air-gapped / corporate user runs a jar they
      // provisioned themselves, and how CI launches a jar freshly built from the
      // tree instead of a published release. A configured path that is missing is
      // a hard error (resolveExplicitJarPath) - never a silent fall-back to a
      // download, which would hide the intended jar being absent.
      var explicitJarPath = options.jarPath || process.env.MOCKSERVER_JAR_PATH;
      var jarReady;
      if (explicitJarPath) {
        try {
          jarReady = Q.resolve(resolveExplicitJarPath(
            explicitJarPath,
            options.jarPath ? 'jarPath option' : 'MOCKSERVER_JAR_PATH',
            logLevel || options.verbose));
        } catch (error) {
          jarReady = Q.reject(error);
        }
      } else {
        // double check the jar has already been downloaded, then resolve the jar
        // for the specific version being launched - a wildcard version would match
        // every downloaded version and push an array, which spawn joins with a
        // comma into an invalid "a.jar,b.jar" path.
        jarReady = require('./downloadJar').downloadJar(mockServerVersion, artifactoryHost, artifactoryPath, logLevel).then(function () {
          return resolveJarPath('mockserver-netty-' + mockServerVersion + '-jar-with-dependencies.jar');
        });
      }

      jarReady.then(function (jarFile) {

        var spawn = require('child_process').spawn;
        var commandLineOptions = ['-Dfile.encoding=UTF-8'];
        if (options.initializationJsonPath) {
          commandLineOptions.push('-Dmockserver.initializationJsonPath=' + options.initializationJsonPath);
        }
        if (options.javaDebugPort) {
          commandLineOptions.push('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=' + options.javaDebugPort);
        }
        
        if (options.jvmOptions) {
          if (Array.isArray(options.jvmOptions)) {
            commandLineOptions.push(...options.jvmOptions);
          } else {
            commandLineOptions.push(...options.jvmOptions.split(' '));
          }
        }
        commandLineOptions.push('-jar');
        commandLineOptions.push(jarFile);
        if (options.serverPort) {
          commandLineOptions.push("-serverPort");
          commandLineOptions.push(options.serverPort);
          port = port || options.serverPort;
        }
        if (options.proxyRemotePort) {
          commandLineOptions.push("-proxyRemotePort");
          commandLineOptions.push(options.proxyRemotePort);
        }
        if (options.proxyRemoteHost) {
          commandLineOptions.push("-proxyRemoteHost");
          commandLineOptions.push(options.proxyRemoteHost);
        }
        if (logLevel) {
          commandLineOptions.push("-logLevel");
          commandLineOptions.push(logLevel);
        }
        if (options.verbose) {
          console.log('Running \'java ' + commandLineOptions.join(' ') + '\'');
        }
        if (!options.runForked) {
          var exitHandler = function(config, err) {
            return stop_mockserver(config.options).then(function () {
              if (err) {
                console.log(err.stack);
              }
              if (config.exit) {
                process.exit();
              }
            });
          };
  
          // stop mockserver for uncaught exceptions
          process.on('uncaughtException', exitHandler.bind(null, {exit: true, options: options}));
        }
        // Always pipe stdout+stderr so we can capture them into the bounded ring buffer. Preserve the
        // previous surfacing behaviour: stdout is echoed to the parent only when verbose, stderr is
        // always echoed (as it was when routed directly to process.stderr).
        mockServerOutput = '';
        mockServerExit = undefined;
        mockServer = spawn('java', commandLineOptions, {
          stdio: ['ignore', 'pipe', 'pipe']
        });
        mockServer.stdout.on('data', function (chunk) {
          appendCapturedOutput(chunk);
          if (options.verbose) {
            process.stdout.write(chunk);
          }
        });
        mockServer.stderr.on('data', function (chunk) {
          appendCapturedOutput(chunk);
          process.stderr.write(chunk);
        });
        mockServer.once('exit', function (code, signal) {
          mockServerExit = { code: code, signal: signal };
        });
  
      }).then(function () {
        return checkStarted({
          method: 'PUT',
          host: "localhost",
          path: "/mockserver/retrieve?type=ACTIVE_EXPECTATIONS",
          port: port
        }, startupRetries, deferred, options.verbose);
      }, function (error) {
        deferred.reject(error);
      });
  
      return deferred.promise;
    }
  
    return {
      start_mockserver: start_mockserver,
      stop_mockserver: stop_mockserver,
      // Diagnostics for readiness probes (e.g. test/waitForTlsReady.js): the launched child process, its
      // exit status once it has died (undefined while running), and the captured stdout+stderr tail.
      getMockServerProcess: function () {
        return mockServer;
      },
      getMockServerExit: function () {
        return mockServerExit;
      },
      getMockServerOutput: function (maxLines) {
        return capturedOutputTail(maxLines);
      }
    };
  })();
  
