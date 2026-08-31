(function () {
  if (window.__kiraziumCaptureInstalled) {
    try { KiraziumBridge.status('Dinleme zaten aktif. Bölümü aç veya sayfayı yenile.'); } catch (_) {}
    return 'already-installed';
  }
  window.__kiraziumCaptureInstalled = true;

  function cleanLink(v) {
    return typeof v === 'string' && /^https?:\/\//i.test(v) ? v : '';
  }

  function sanitizeResponse(raw, sourceUrl) {
    try {
      var root = raw && typeof raw === 'object' ? raw : JSON.parse(raw);
      var data = root && root.data && typeof root.data === 'object' ? root.data : root;
      var videos = [];
      var subtitles = [];

      var groups = Array.isArray(data && data.groups) ? data.groups : [];
      groups.forEach(function (group) {
        var items = Array.isArray(group && group.items) ? group.items : [];
        items.forEach(function (item) {
          var link = cleanLink(item && item.link);
          if (!link) return;
          videos.push({
            quality: item && item.quality != null ? String(item.quality) : '',
            link: link,
            group: group && group.group != null ? String(group.group) : '',
            name: group && group.name != null ? String(group.name) : '',
            type: group && group.type != null ? String(group.type) : '',
            platform: group && group.platform != null ? String(group.platform) : ''
          });
        });
      });

      function addSubtitle(s) {
        if (!s || typeof s !== 'object') return;
        var link = cleanLink(s.link) || cleanLink(s.url) || cleanLink(s.path);
        if (!link) return;
        subtitles.push({
          name: s.name != null ? String(s.name) : (s.label != null ? String(s.label) : ''),
          group: s.group != null ? String(s.group) : '',
          link: link
        });
      }

      var directSubs = Array.isArray(data && data.subtitles) ? data.subtitles : [];
      directSubs.forEach(addSubtitle);

      var seasons = Array.isArray(data && data.seasons) ? data.seasons : [];
      seasons.forEach(function (season) {
        var episodes = Array.isArray(season && season.episodes) ? season.episodes : [];
        episodes.forEach(function (episode) {
          var nestedSubs = Array.isArray(episode && episode.subtitles) ? episode.subtitles : [];
          nestedSubs.forEach(addSubtitle);
        });
      });

      if (!videos.length && !subtitles.length) return;
      KiraziumBridge.capture(JSON.stringify({
        source: sourceUrl || '',
        payload: {
          videos: videos,
          subtitles: subtitles
        }
      }));
    } catch (_) {
      // Ignore non-JSON and unrelated responses.
    }
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function () {
      var args = arguments;
      var requestUrl = '';
      try {
        requestUrl = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
      } catch (_) {}
      return originalFetch.apply(this, args).then(function (response) {
        try {
          var finalUrl = (response && response.url) || requestUrl || '';
          if (/\/anime\/(source|bulk-source)/i.test(finalUrl)) {
            response.clone().text().then(function (text) { sanitizeResponse(text, finalUrl); });
          }
        } catch (_) {}
        return response;
      });
    };
  }

  var XHR = window.XMLHttpRequest;
  if (XHR && XHR.prototype) {
    var originalOpen = XHR.prototype.open;
    var originalSend = XHR.prototype.send;

    XHR.prototype.open = function (method, url) {
      try { this.__kiraziumUrl = String(url || ''); } catch (_) { this.__kiraziumUrl = ''; }
      return originalOpen.apply(this, arguments);
    };

    XHR.prototype.send = function () {
      try {
        this.addEventListener('load', function () {
          try {
            var finalUrl = this.responseURL || this.__kiraziumUrl || '';
            if (!/\/anime\/(source|bulk-source)/i.test(finalUrl)) return;
            if (typeof this.responseText === 'string') sanitizeResponse(this.responseText, finalUrl);
          } catch (_) {}
        });
      } catch (_) {}
      return originalSend.apply(this, arguments);
    };
  }

  try { KiraziumBridge.status('Dinleme aktif. Bölümü aç veya yenile.'); } catch (_) {}
  return 'installed';
})();
