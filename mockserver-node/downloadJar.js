/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

(function () {
    "use strict";

    // Fail a download that stalls rather than waiting on it forever. This is an
    // IDLE timeout, re-armed on every chunk received, so it never interrupts a
    // slow-but-progressing transfer of a ~100MB jar - it only fires when the
    // connection has produced nothing at all for this long, which is the case a
    // socket-level error cannot report (a peer that neither sends nor closes).
    // Raise it with MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS behind a proxy that
    // legitimately pauses for longer than this before it starts streaming.
    var DEFAULT_IDLE_TIMEOUT_MILLIS = 60000;
    // A jar is a ZIP archive, so a complete one starts with the ZIP local file
    // header signature. Checking it rejects the "successful" download of an
    // error page - a captive portal or TLS-inspecting proxy answering 200 with
    // HTML - which would otherwise be renamed into place and, because the only
    // later freshness test is whether the file exists, cached forever.
    var ZIP_LOCAL_FILE_HEADER = [0x50, 0x4B, 0x03, 0x04];
    var MINIMUM_JAR_BYTES = 1024;
    // Only a partial file this old can safely be assumed abandoned. Anything
    // younger might belong to a download running right now in another process,
    // and deleting one of those is the very fault this module exists to avoid.
    var ABANDONED_PARTIAL_MILLIS = 60 * 60 * 1000;

    /**
     * @returns {number} the idle timeout to use, overridable for tests and for
     *                   proxies that stall longer than the default
     */
    function idleTimeoutMillis() {
      var configured = parseInt(process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS, 10);
      return configured > 0 ? configured : DEFAULT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * @param {string} file path to check
     * @returns {boolean} true if the file starts with the ZIP local file header
     */
    function startsWithZipHeader(file) {
      var fs = require('fs');
      var header = Buffer.alloc(ZIP_LOCAL_FILE_HEADER.length);
      var descriptor = fs.openSync(file, 'r');
      try {
        if (fs.readSync(descriptor, header, 0, header.length, 0) < header.length) {
          return false;
        }
      } finally {
        fs.closeSync(descriptor);
      }
      return ZIP_LOCAL_FILE_HEADER.every(function (byte, index) {
        return header[index] === byte;
      });
    }

    /**
     * Choose the directory to keep jars in.
     *
     * This package's own directory is used whenever it is writable, so the jar
     * is found again from any working directory (see resolveJarPath in
     * index.js). A globally-installed, root-owned module used by a non-root
     * user - or a read-only node_modules layer - is not writable, and there the
     * working directory is used instead, which resolveJarPath also looks in.
     *
     * @param {string} moduleDirectory this module's directory
     * @returns {string} the directory downloads are written to
     */
    function downloadDirectory(moduleDirectory) {
      var fs = require('fs');
      try {
        fs.accessSync(moduleDirectory, fs.constants.W_OK);
        return moduleDirectory;
      } catch (notWritable) {
        return process.cwd();
      }
    }

    /**
     * Remove partial files left behind by a download that was killed before it
     * could clean up after itself - each one can be ~100MB. Only files old
     * enough that no live download could own them are removed.
     *
     * @param {string} directory the directory holding the jars
     * @param {string} jarName the jar whose partial files should be swept
     */
    function sweepAbandonedPartials(directory, jarName) {
      var fs = require('fs');
      var path = require('path');
      var cutoff = Date.now() - ABANDONED_PARTIAL_MILLIS;
      var entries;
      try {
        entries = fs.readdirSync(directory);
      } catch (unreadable) {
        return; // best effort
      }
      entries.forEach(function (entry) {
        if (entry.indexOf(jarName + '.') !== 0 || !/\.part$/.test(entry)) {
          return;
        }
        var partialPath = path.join(directory, entry);
        try {
          if (fs.statSync(partialPath).mtimeMs < cutoff) {
            fs.unlinkSync(partialPath);
          }
        } catch (ignore) { /* best effort - a concurrent sweep may have won */ }
      });
    }

    function downloadJar(version, artifactoryHost, artifactoryPath, logLevel) {
      var Q = require('q');
      var deferred = Q.defer();
      var https = require('follow-redirects').https;
      var crypto = require('crypto');
      var fs = require('fs');
      var path = require('path');
      var jarName = 'mockserver-netty-' + version + '-jar-with-dependencies.jar';
      // Store the jar in a fixed directory rather than relative to the working
      // directory, so that it can be located deterministically no matter where
      // the caller runs from. Earlier releases wrote it relative to the working
      // directory while checking for it here, so a caller running from anywhere
      // else re-downloaded it every time.
      var directory = downloadDirectory(__dirname);
      var dest = path.join(directory, jarName);
      var snapshot = version.indexOf("SNAPSHOT") !== -1;
      var options = {
        host: artifactoryHost,
        path: artifactoryPath && !snapshot ?
          artifactoryPath + version + "/mockserver-netty-" + version + "-jar-with-dependencies.jar" :
          "/service/local/artifact/maven/redirect?r=" + (snapshot ? "snapshots" : "releases") + "&g=org.mock-server&a=mockserver-netty&c=jar-with-dependencies&v=" + version,
        port: 443
      };

      // A SNAPSHOT jar changes without its version changing, so it is always
      // re-fetched; any other version is fetched only when it is missing.
      //
      // Nothing is ever deleted. The finished download is renamed over whatever
      // is already there in one step, so no concurrent call can ever catch the
      // jar absent - deleting it first, even only for SNAPSHOTs, reopens exactly
      // the window this code exists to close. Jars for OTHER versions are left
      // alone for the same reason: mockServerVersion is a per-call option, so
      // removing them would delete a jar that a concurrent call (or the very
      // next call) is about to launch.
      if (snapshot || !fs.existsSync(dest)) {
        if (logLevel) {
          console.log('Fetching ' + JSON.stringify(options, null, 2));
        }
        sweepAbandonedPartials(directory, jarName);
        // Download to a uniquely-named temporary file and rename into place only
        // once the body is complete and verified, so a concurrent process never
        // observes a half-written jar as if it were a usable one. The name needs
        // random bytes rather than just the pid, because pids are namespaced -
        // two containers sharing one bind-mounted install both run as pid 1 and
        // would otherwise write to the same path. Opening with 'wx' means an
        // existing file (or a symlink planted at this path) fails loudly instead
        // of being truncated or followed.
        var partial = dest + '.' + crypto.randomBytes(6).toString('hex') + '.part';
        var writeStream = null;
        var response = null;
        var idleTimer = null;
        var settled = false;
        // Only true once the exclusive open has succeeded, i.e. once this call
        // is the one that created the file. If the open failed because
        // something was already at that path, cleaning up must not remove it -
        // refusing to write through a planted file and then deleting it anyway
        // would defeat the point of opening exclusively.
        var createdPartial = false;

        var clearIdleTimer = function () {
          if (idleTimer) {
            clearTimeout(idleTimer);
            idleTimer = null;
          }
        };
        var req = https.request(options);
        var armIdleTimer = function () {
          clearIdleTimer();
          idleTimer = setTimeout(function () {
            fail('Fetching ' + JSON.stringify(options, null, 2) + ' failed with error ' +
              'nothing received for ' + (idleTimeoutMillis() / 1000) + ' seconds');
          }, idleTimeoutMillis());
        };
        var fail = function (message) {
          if (settled) {
            return;
          }
          settled = true;
          clearIdleTimer();
          // Tear down the source as well as the sink. Leaving the response
          // attached keeps it flowing after pipe unpipes, so every remaining
          // chunk of a ~100MB body would keep arriving - re-arming the idle
          // timer and holding the event loop open - long after the caller has
          // been told the download failed. Destroying the request also releases
          // the socket on the non-2xx path, where the body is never read.
          if (response) {
            response.removeListener('data', armIdleTimer);
            response.unpipe();
            response.destroy();
          }
          req.destroy();
          if (writeStream) {
            writeStream.destroy();
          }
          if (createdPartial) {
            try {
              fs.unlinkSync(partial);
            } catch (ignore) { /* best effort */ }
          }
          if (logLevel) {
            console.error(message);
          }
          deferred.reject(new Error(message));
        };

        req.once('error', function (error) {
          fail('Fetching ' + JSON.stringify(options, null, 2) + ' failed with error ' + error);
        });

        req.once('response', function (res) {
          response = res;
          if (res.statusCode < 200 || res.statusCode >= 300) {
            fail('Fetching ' + JSON.stringify(options, null, 2) + ' failed with HTTP status code ' + res.statusCode);
          } else {
            // Create the file synchronously rather than letting the write
            // stream open it asynchronously. The stream's open completes after
            // createWriteStream returns, so a failure arriving in between would
            // leave this call unsure whether the file had been created - and
            // therefore unable to say whether cleaning it up would remove its
            // own partial or somebody else's file. Opening here settles that
            // before anything else can happen.
            var descriptor;
            try {
              descriptor = fs.openSync(partial, 'wx');
            } catch (error) {
              fail('Saving ' + dest + ' failed with error ' + error);
              return;
            }
            createdPartial = true;
            writeStream = fs.createWriteStream(partial, { fd: descriptor });
            res.pipe(writeStream);

            // Re-arm on every chunk, so the timeout only fires on a connection
            // that has gone silent rather than on one that is merely slow.
            res.on('data', armIdleTimer);
            // Without this a connection dropped mid-body leaves the promise
            // pending forever (pipe does not end the write stream on a source
            // error) and leaves a truncated file behind.
            res.on('error', function (error) {
              fail('Fetching ' + JSON.stringify(options, null, 2) + ' failed with error ' + error);
            });
            writeStream.on('error', function (error) {
              fail('Saving ' + dest + ' failed with error ' + error);
            });
            writeStream.on('close', function () {
              if (settled) {
                return;
              }
              clearIdleTimer();
              var size;
              var isJar;
              try {
                size = fs.statSync(partial).size;
                isJar = size >= MINIMUM_JAR_BYTES && startsWithZipHeader(partial);
              } catch (error) {
                // The partial can be gone entirely - removed by something else
                // while the download ran. Reject rather than throwing out of
                // this handler, where an uncaught exception would be taken by
                // the uncaughtException handler in index.js as a reason to tear
                // the whole process down while this promise never settles.
                fail('Saving ' + dest + ' failed with error ' + error);
                return;
              }
              if (!isJar) {
                fail('Fetching ' + JSON.stringify(options, null, 2) + ' returned ' + size +
                  ' bytes that are not a jar - check whether a proxy answered with an error page');
                return;
              }
              try {
                fs.renameSync(partial, dest);
              } catch (error) {
                fail('Saving ' + dest + ' failed with error ' + error);
                return;
              }
              settled = true;
              if (logLevel) {
                console.log('Saved ' + dest + ' from ' + JSON.stringify(options, null, 2));
              }
              deferred.resolve();
            });
          }
        });

        armIdleTimer();
        req.end();
      } else {
        if (logLevel) {
          console.log('Skipping ' + JSON.stringify(options, null, 2) + ' as file already downloaded');
        }
        deferred.resolve();
      }

      return deferred.promise;
    }

    module.exports = {
      downloadJar: downloadJar,

      // Exported for testing only (not part of the public contract)
      _internal: {
        startsWithZipHeader: startsWithZipHeader,
        downloadDirectory: downloadDirectory,
        sweepAbandonedPartials: sweepAbandonedPartials,
        idleTimeoutMillis: idleTimeoutMillis,
        DEFAULT_IDLE_TIMEOUT_MILLIS: DEFAULT_IDLE_TIMEOUT_MILLIS,
        MINIMUM_JAR_BYTES: MINIMUM_JAR_BYTES,
        ABANDONED_PARTIAL_MILLIS: ABANDONED_PARTIAL_MILLIS
      }
    };
  })();
