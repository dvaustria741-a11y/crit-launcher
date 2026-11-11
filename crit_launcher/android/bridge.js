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
  // The desktop layout needs about 900 CSS px; scale down on narrower screens.
  var meta = document.querySelector('meta[name="viewport"]');
  if (!meta) { meta = document.createElement("meta"); meta.name = "viewport"; document.head.appendChild(meta); }
  meta.content = (screen.width < 900 ? "width=900" : "width=device-width") + ", initial-scale=1, viewport-fit=cover";
  window.addEventListener("load", function () {
    window.dispatchEvent(new Event("pywebviewready"));
  });
})();
