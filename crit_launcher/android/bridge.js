/* Android shim for window.pywebview.api: forwards calls to the Kotlin/Python bridge. */
(function () {
  var METHODS = __CRIT_METHODS__;
  var pending = {};
  var nextId = 1;
  window.__critResolve = function (id, json) {
    var cb = pending[id];
    if (!cb) return;
    delete pending[id];
    try { cb.resolve(JSON.parse(json)); } catch (e) { cb.reject(e); }
  };
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.CritBridge.call(id, method, JSON.stringify(args));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }
  var api = {};
  METHODS.forEach(function (name) {
    api[name] = function () { return call(name, Array.prototype.slice.call(arguments)); };
  });
  window.pywebview = { api: api };
  // Mobile layout: real device width, no pinch-zoom (the UI is responsive; see mobile.css).
  document.documentElement.classList.add("crit-android");
  var meta = document.querySelector('meta[name="viewport"]');
  if (!meta) { meta = document.createElement("meta"); meta.name = "viewport"; document.head.appendChild(meta); }
  meta.content = "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no";
  window.addEventListener("load", function () {
    window.dispatchEvent(new Event("pywebviewready"));
  });
})();
