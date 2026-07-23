/*
 * Hermetic unit tests for the MockServer jar downloader (downloadJar.js).
 *
 * No network access: the https implementation is replaced on the cached
 * follow-redirects module, which downloadJar resolves at call time, so each
 * test drives a scripted response. Every test uses its own throwaway version
 * number and removes what it creates, so these can run alongside the tests
 * that launch a real MockServer from a real jar in the same directory - which
 * is itself part of what is under test here, since a download must never touch
 * a jar belonging to another version.
 */
'use strict';

var test = require('node:test');
var assert = require('node:assert');
var fs = require('fs');
var os = require('os');
var path = require('path');
var stream = require('stream');
var EventEmitter = require('events');

var followRedirects = require('follow-redirects');
var downloadJarModule = require('../downloadJar');
var downloadJar = downloadJarModule.downloadJar;
var internal = downloadJarModule._internal;

var MODULE_DIR = path.join(__dirname, '..');
var HOST = 'repo1.maven.org';
var ARTIFACTORY_PATH = '/maven2/org/mock-server/mockserver-netty/';
var realHttps = followRedirects.https;

// ---------- helpers ----------

function jarPath(version) {
  return path.join(MODULE_DIR, 'mockserver-netty-' + version + '-jar-with-dependencies.jar');
}

/** A payload that passes the jar check: ZIP magic plus enough bytes. */
function jarBytes(fill) {
  return Buffer.concat([Buffer.from([0x50, 0x4B, 0x03, 0x04]), Buffer.alloc(4096, fill || 0x41)]);
}

function partialFilesFor(version) {
  var prefix = path.basename(jarPath(version)) + '.';
  return fs.readdirSync(MODULE_DIR).filter(function (entry) {
    return entry.indexOf(prefix) === 0 && /\.part$/.test(entry);
  });
}

function remove(file) {
  try {
    fs.unlinkSync(file);
  } catch (ignore) { /* not there */ }
}

function cleanUp(version) {
  remove(jarPath(version));
  partialFilesFor(version).forEach(function (entry) {
    remove(path.join(MODULE_DIR, entry));
  });
}

/**
 * Install a scripted https implementation.
 *
 * @param {Object} script
 * @param {number} script.statusCode  status to answer with
 * @param {Buffer[]} [script.chunks]  body chunks to write
 * @param {boolean} [script.stall]    leave the response open after the chunks
 * @param {number} [script.chunkDelayMillis]  pause between chunks, for a slow
 *                                            but progressing transfer
 * @param {boolean} [script.neverRespond]  accept the request and then say
 *                                         nothing at all - no headers, no
 *                                         body, no close
 * @returns {Object} state observable by the test: the request, the response,
 *                   and whether the request was destroyed
 */
function respondWith(script) {
  var state = { request: null, response: null, destroyed: false };
  followRedirects.https = {
    request: function () {
      var request = new EventEmitter();
      state.request = request;
      request.destroy = function (error) {
        state.destroyed = true;
        if (error) {
          request.emit('error', error);
        }
      };
      request.end = function () {
        if (script.neverRespond) {
          return;
        }
        setImmediate(function () {
          var response = new stream.PassThrough();
          response.statusCode = script.statusCode;
          state.response = response;
          request.emit('response', response);
          if (script.resetImmediately) {
            // fail in the same tick the response arrived, before anything the
            // download does asynchronously has had a chance to complete
            response.destroy(new Error('reset before open'));
            return;
          }
          var chunks = script.chunks || [];
          if (!script.chunkDelayMillis) {
            chunks.forEach(function (chunk) {
              response.write(chunk);
            });
            if (!script.stall) {
              response.end();
            }
            return;
          }
          var index = 0;
          (function writeNext() {
            if (state.destroyed || response.destroyed) {
              return;
            }
            if (index >= chunks.length) {
              if (!script.stall) {
                response.end();
              }
              return;
            }
            response.write(chunks[index]);
            index += 1;
            setTimeout(writeNext, script.chunkDelayMillis);
          })();
        });
      };
      return request;
    }
  };
  return state;
}

function restoreHttps() {
  followRedirects.https = realHttps;
}

async function rejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  return null;
}

// ---------- the behaviour that caused the original defect ----------

test('a download for one version leaves another version\'s jar untouched', { timeout: 15000 }, async function (t) {
  var other = '0.0.1-other';
  var wanted = '0.0.2-wanted';
  t.after(function () { restoreHttps(); cleanUp(other); cleanUp(wanted); });

  var otherBytes = jarBytes(0x42);
  fs.writeFileSync(jarPath(other), otherBytes);

  respondWith({ statusCode: 200, chunks: [jarBytes(0x43)] });
  await downloadJar(wanted, HOST, ARTIFACTORY_PATH, null);

  assert.ok(fs.existsSync(jarPath(other)), 'the other version\'s jar must still be there');
  assert.deepStrictEqual(fs.readFileSync(jarPath(other)), otherBytes, 'and must be byte-for-byte unchanged');
  assert.ok(fs.existsSync(jarPath(wanted)), 'the requested version must have been downloaded');
});

test('a valid jar is renamed into place leaving no partial file behind', { timeout: 15000 }, async function (t) {
  var version = '0.0.3-valid';
  t.after(function () { restoreHttps(); cleanUp(version); });

  var bytes = jarBytes();
  respondWith({ statusCode: 200, chunks: [bytes] });
  await downloadJar(version, HOST, ARTIFACTORY_PATH, null);

  assert.deepStrictEqual(fs.readFileSync(jarPath(version)), bytes);
  assert.deepStrictEqual(partialFilesFor(version), []);
});

test('an existing jar is used as-is rather than re-downloaded', { timeout: 15000 }, async function (t) {
  var version = '0.0.4-cached';
  t.after(function () { restoreHttps(); cleanUp(version); });

  fs.writeFileSync(jarPath(version), jarBytes());
  // any request at all would fail, so resolving proves none was made
  var state = respondWith({ statusCode: 500 });
  await downloadJar(version, HOST, ARTIFACTORY_PATH, null);

  assert.strictEqual(state.request, null, 'no request should have been made');
});

test('a failed SNAPSHOT re-fetch leaves the previous jar in place', { timeout: 15000 }, async function (t) {
  var version = '0.0.5-SNAPSHOT';
  t.after(function () { restoreHttps(); cleanUp(version); });

  var bytes = jarBytes(0x44);
  fs.writeFileSync(jarPath(version), bytes);

  respondWith({ statusCode: 500 });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /HTTP status code 500/);
  assert.ok(fs.existsSync(jarPath(version)), 'the previous SNAPSHOT jar must survive a failed re-fetch');
  assert.deepStrictEqual(fs.readFileSync(jarPath(version)), bytes);
});

test('a SNAPSHOT is re-fetched and replaced even though it already exists', { timeout: 15000 }, async function (t) {
  var version = '0.0.6-SNAPSHOT';
  t.after(function () { restoreHttps(); cleanUp(version); });

  fs.writeFileSync(jarPath(version), jarBytes(0x45));
  var refreshed = jarBytes(0x46);
  respondWith({ statusCode: 200, chunks: [refreshed] });
  await downloadJar(version, HOST, ARTIFACTORY_PATH, null);

  assert.deepStrictEqual(fs.readFileSync(jarPath(version)), refreshed);
});

// ---------- rejecting a download that is not a jar ----------

test('a 200 carrying an error page is rejected and nothing is cached', { timeout: 15000 }, async function (t) {
  var version = '0.0.7-htmlpage';
  t.after(function () { restoreHttps(); cleanUp(version); });

  var html = Buffer.from('<html><body>' + 'x'.repeat(4000) + '</body></html>');
  respondWith({ statusCode: 200, chunks: [html] });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /are not a jar/);
  assert.ok(!fs.existsSync(jarPath(version)), 'an error page must never be cached as the jar');
  assert.deepStrictEqual(partialFilesFor(version), []);
});

test('a 200 carrying a trivially small body is rejected', { timeout: 15000 }, async function (t) {
  var version = '0.0.8-truncated';
  t.after(function () { restoreHttps(); cleanUp(version); });

  respondWith({ statusCode: 200, chunks: [Buffer.from([0x50, 0x4B, 0x03, 0x04])] });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /are not a jar/);
  assert.ok(!fs.existsSync(jarPath(version)));
});

test('startsWithZipHeader distinguishes a jar from anything else', function (t) {
  var directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-zip-'));
  t.after(function () { fs.rmSync(directory, { recursive: true, force: true }); });

  var jar = path.join(directory, 'looks-like-a-jar');
  fs.writeFileSync(jar, jarBytes());
  assert.strictEqual(internal.startsWithZipHeader(jar), true);

  var html = path.join(directory, 'error-page');
  fs.writeFileSync(html, '<html>nope</html>');
  assert.strictEqual(internal.startsWithZipHeader(html), false);

  var tooShort = path.join(directory, 'two-bytes');
  fs.writeFileSync(tooShort, Buffer.from([0x50, 0x4B]));
  assert.strictEqual(internal.startsWithZipHeader(tooShort), false);
});

// ---------- the temporary file ----------

test('a file already at the temporary path is refused rather than overwritten', { timeout: 15000 }, async function (t) {
  var version = '0.0.9-planted';
  var crypto = require('crypto');
  var realRandomBytes = crypto.randomBytes;
  // make the temporary name predictable so a hostile path can be planted at it
  var fixed = Buffer.alloc(6, 0xcd);
  crypto.randomBytes = function () { return fixed; };
  var planted = jarPath(version) + '.' + fixed.toString('hex') + '.part';
  t.after(function () {
    crypto.randomBytes = realRandomBytes;
    restoreHttps();
    remove(planted);
    cleanUp(version);
  });

  fs.writeFileSync(planted, 'do not overwrite me');
  respondWith({ statusCode: 200, chunks: [jarBytes()] });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /EEXIST/);
  assert.strictEqual(fs.readFileSync(planted, 'utf8'), 'do not overwrite me',
    'a file this download did not create must be neither written through nor removed');
  assert.ok(!fs.existsSync(jarPath(version)));
});

test('a symlink planted at the temporary path is not followed', { timeout: 15000 }, async function (t) {
  var version = '0.1.0-symlink';
  var crypto = require('crypto');
  var realRandomBytes = crypto.randomBytes;
  var fixed = Buffer.alloc(6, 0xce);
  crypto.randomBytes = function () { return fixed; };
  var link = jarPath(version) + '.' + fixed.toString('hex') + '.part';
  var directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-symlink-'));
  var victim = path.join(directory, 'victim');
  t.after(function () {
    crypto.randomBytes = realRandomBytes;
    restoreHttps();
    remove(link);
    fs.rmSync(directory, { recursive: true, force: true });
    cleanUp(version);
  });

  fs.writeFileSync(victim, 'original contents');
  fs.symlinkSync(victim, link);
  respondWith({ statusCode: 200, chunks: [jarBytes()] });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /EEXIST/);
  assert.strictEqual(fs.readFileSync(victim, 'utf8'), 'original contents',
    'the symlink target must not have been written through');
  assert.ok(fs.lstatSync(link).isSymbolicLink(),
    'a path this download did not create must not be cleaned up either');
});

test('sweepAbandonedPartials removes only partials too old to be in flight', function (t) {
  var directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-sweep-'));
  t.after(function () { fs.rmSync(directory, { recursive: true, force: true }); });

  var jarName = 'mockserver-netty-1.2.3-jar-with-dependencies.jar';
  var abandoned = path.join(directory, jarName + '.aaaaaaaaaaaa.part');
  var inFlight = path.join(directory, jarName + '.bbbbbbbbbbbb.part');
  var otherVersion = path.join(directory, 'mockserver-netty-9.9.9-jar-with-dependencies.jar.cccccccccccc.part');
  var realJar = path.join(directory, jarName);
  [abandoned, inFlight, otherVersion, realJar].forEach(function (file) {
    fs.writeFileSync(file, 'x');
  });
  var old = (Date.now() - internal.ABANDONED_PARTIAL_MILLIS - 60000) / 1000;
  fs.utimesSync(abandoned, old, old);
  fs.utimesSync(otherVersion, old, old);

  internal.sweepAbandonedPartials(directory, jarName);

  assert.ok(!fs.existsSync(abandoned), 'an old partial for this jar should be swept');
  assert.ok(fs.existsSync(inFlight), 'a fresh partial may belong to a running download');
  assert.ok(fs.existsSync(otherVersion), 'a partial for another version is not ours to remove');
  assert.ok(fs.existsSync(realJar), 'the jar itself must never be swept');
});

// ---------- a connection that goes silent ----------

test('a stalled connection is rejected, and stops downloading once it has failed', { timeout: 15000 }, async function (t) {
  var version = '0.1.1-stalled';
  var previous = process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = '150';
  t.after(function () {
    if (previous === undefined) {
      delete process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
    } else {
      process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = previous;
    }
    restoreHttps();
    cleanUp(version);
  });

  var state = respondWith({ statusCode: 200, chunks: [Buffer.from('PK')], stall: true });
  var started = Date.now();
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /nothing received for/);
  assert.ok(Date.now() - started < 5000, 'the configured idle timeout must be honoured');
  assert.ok(!fs.existsSync(jarPath(version)));
  assert.deepStrictEqual(partialFilesFor(version), []);

  // the failure must stop the transfer, not merely report it
  assert.ok(state.destroyed, 'the request must be destroyed');
  assert.ok(state.response.destroyed, 'the response must be destroyed');
  assert.strictEqual(state.response.listenerCount('data'), 0,
    'the response must not still be consumed after the download has failed');
});

test('a slow but progressing download is never interrupted by the idle timeout', { timeout: 15000 }, async function (t) {
  var version = '0.1.5-slow';
  var previous = process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = '150';
  t.after(function () {
    if (previous === undefined) {
      delete process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
    } else {
      process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = previous;
    }
    restoreHttps();
    cleanUp(version);
  });

  // five chunks 100ms apart: every gap is under the timeout, but the transfer
  // as a whole takes several times longer than it. This is what makes the
  // timeout an IDLE one rather than a limit on the whole download - a real
  // ~100MB jar takes far longer than any sane idle timeout.
  var body = jarBytes();
  var chunks = [];
  var chunkSize = Math.ceil(body.length / 5);
  for (var offset = 0; offset < body.length; offset += chunkSize) {
    chunks.push(body.subarray(offset, offset + chunkSize));
  }
  respondWith({ statusCode: 200, chunks: chunks, chunkDelayMillis: 100 });

  var started = Date.now();
  await downloadJar(version, HOST, ARTIFACTORY_PATH, null);
  var elapsed = Date.now() - started;

  assert.ok(elapsed > 300, 'the transfer must have outlasted the idle timeout, took ' + elapsed + 'ms');
  assert.deepStrictEqual(fs.readFileSync(jarPath(version)), body);
});

test('a connection that never responds at all is rejected', { timeout: 15000 }, async function (t) {
  var version = '0.1.6-silent';
  var previous = process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = '150';
  t.after(function () {
    if (previous === undefined) {
      delete process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
    } else {
      process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = previous;
    }
    restoreHttps();
    cleanUp(version);
  });

  // no headers, no body, no close - nothing ever arrives to arm a timer, so
  // only the arming that happens up front can rescue this
  var state = respondWith({ neverRespond: true });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /nothing received for/);
  assert.ok(state.destroyed, 'the request must be destroyed');
  assert.ok(!fs.existsSync(jarPath(version)));
  assert.deepStrictEqual(partialFilesFor(version), []);
});

test('a write failure stops the transfer instead of draining the whole body', { timeout: 15000 }, async function (t) {
  var version = '0.1.2-writefail';
  var crypto = require('crypto');
  var realRandomBytes = crypto.randomBytes;
  var fixed = Buffer.alloc(6, 0xcf);
  crypto.randomBytes = function () { return fixed; };
  var planted = jarPath(version) + '.' + fixed.toString('hex') + '.part';
  t.after(function () {
    crypto.randomBytes = realRandomBytes;
    restoreHttps();
    remove(planted);
    cleanUp(version);
  });

  // the planted file makes the exclusive open fail, which is the write-side
  // failure most likely to be hit in practice
  fs.writeFileSync(planted, 'in the way');
  var state = respondWith({ statusCode: 200, chunks: [jarBytes()], stall: true });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /EEXIST/);
  assert.ok(state.destroyed, 'the request must be destroyed rather than left transferring');
  assert.ok(state.response.destroyed, 'the response must be destroyed');
  assert.strictEqual(state.response.listenerCount('data'), 0,
    'the response must not still be consumed after the download has failed');
});

test('a failure in the same tick as the response leaves no orphaned partial file', { timeout: 15000 }, async function (t) {
  var version = '0.1.7-earlyreset';
  t.after(function () { restoreHttps(); cleanUp(version); });

  respondWith({ statusCode: 200, resetImmediately: true });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /reset before open/);
  // let any open() still in flight on the libuv threadpool land before looking.
  // Without this the assertion can sample the directory before the syscall
  // completes and pass against a regression it should catch.
  await new Promise(function (resolve) { setImmediate(resolve); });
  // the file is created before the download can fail, so it is this call's to
  // clean up however early the failure arrives
  assert.deepStrictEqual(partialFilesFor(version), [],
    'a partial file created by this download must be removed no matter when it fails');
  assert.ok(!fs.existsSync(jarPath(version)));
});

test('a non-2xx response destroys the request rather than leaking the socket', { timeout: 15000 }, async function (t) {
  var version = '0.1.3-notfound';
  t.after(function () { restoreHttps(); cleanUp(version); });

  var state = respondWith({ statusCode: 404, stall: true });
  var error = await rejection(downloadJar(version, HOST, ARTIFACTORY_PATH, null));

  assert.match(error.message, /HTTP status code 404/);
  assert.ok(state.destroyed, 'the request must be destroyed');
  assert.ok(state.response.destroyed, 'the unread response body must be destroyed');
});

test('a connection dropped mid-body rejects and discards the partial file', { timeout: 15000 }, async function (t) {
  var version = '0.1.4-dropped';
  t.after(function () { restoreHttps(); cleanUp(version); });

  var state = respondWith({ statusCode: 200, chunks: [jarBytes()], stall: true });
  var pending = downloadJar(version, HOST, ARTIFACTORY_PATH, null);
  await new Promise(function (resolve) { setTimeout(resolve, 50); });
  state.response.destroy(new Error('connection reset'));
  var error = await rejection(pending);

  assert.match(error.message, /connection reset/);
  assert.ok(!fs.existsSync(jarPath(version)));
  assert.deepStrictEqual(partialFilesFor(version), []);
});

// ---------- configuration ----------

test('the idle timeout is configurable and falls back to the default', function (t) {
  var previous = process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
  t.after(function () {
    if (previous === undefined) {
      delete process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
    } else {
      process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = previous;
    }
  });

  delete process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS;
  assert.strictEqual(internal.idleTimeoutMillis(), internal.DEFAULT_IDLE_TIMEOUT_MILLIS);

  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = '250';
  assert.strictEqual(internal.idleTimeoutMillis(), 250);

  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = 'not-a-number';
  assert.strictEqual(internal.idleTimeoutMillis(), internal.DEFAULT_IDLE_TIMEOUT_MILLIS);

  process.env.MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS = '-1';
  assert.strictEqual(internal.idleTimeoutMillis(), internal.DEFAULT_IDLE_TIMEOUT_MILLIS);
});

test('downloadDirectory prefers the module directory and falls back when it is not writable', function (t) {
  var readOnly = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-readonly-'));
  t.after(function () {
    try { fs.chmodSync(readOnly, 0o700); } catch (ignore) { /* already gone */ }
    fs.rmSync(readOnly, { recursive: true, force: true });
  });

  assert.strictEqual(internal.downloadDirectory(MODULE_DIR), MODULE_DIR);
  assert.strictEqual(internal.downloadDirectory(path.join(MODULE_DIR, 'no-such-directory')), process.cwd());

  // the case the documentation promises: the directory is there but cannot be
  // written to, as with a root-owned global install used by another user
  fs.chmodSync(readOnly, 0o500);
  var stillWritable = true;
  try {
    fs.accessSync(readOnly, fs.constants.W_OK);
  } catch (notWritable) {
    stillWritable = false;
  }
  if (stillWritable) {
    // running as root, where the permission bits do not deny access
    t.skip('cannot make a directory unwritable for this user');
    return;
  }
  assert.strictEqual(internal.downloadDirectory(readOnly), process.cwd());
});
