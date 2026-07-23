/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

module.exports = (function () {

    var mockServer;
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
  
      // double check the jar has already been downloaded
      require('./downloadJar').downloadJar(mockServerVersion, artifactoryHost, artifactoryPath, logLevel).then(function () {
  
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
        // Resolve the jar for the specific version being launched - a wildcard
        // version would match every downloaded version and push an array, which
        // spawn joins with a comma into an invalid "a.jar,b.jar" path.
        commandLineOptions.push(resolveJarPath('mockserver-netty-' + mockServerVersion + '-jar-with-dependencies.jar'));
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
        mockServer = spawn('java', commandLineOptions, {
          stdio: ['ignore', (options.verbose ? process.stdout : 'ignore'), process.stderr]
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
      stop_mockserver: stop_mockserver
    };
  })();
  
