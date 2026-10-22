// The test server of the kiteplayer-web-worker browser tests, beside the clips under /testmedia/.
// It keeps the requests for clips, and serves live HLS and DASH manifests that grow with time, a
// manifest of several DASH periods, and a redirect. KitePlayerWorkerBrowserTest reads it under /kite-probe/.

/** Returns a handler that answers a request and returns true, or returns false to let the files answer. */
export function probeHandler() {
  const probe = { asked: [], manifests: 0, periodFetches: 0, epoch: Date.now() };

  const liveHls = () => {
    const count = Math.min(6, 2 + Math.floor((Date.now() - probe.epoch) / 2000));
    const lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:0"];
    for (let i = 0; i < count; i++) lines.push("#EXTINF:2.000000,", "/testmedia/hls/ts-0-" + i + ".ts");
    if (count === 6) lines.push("#EXT-X-ENDLIST");
    return lines.join("\n") + "\n";
  };

  const liveDash = (origin, ended) => {
    const set = (type, id, attributes) =>
      '<AdaptationSet contentType="' + type + '"><Representation id="' + id + '" ' + attributes + '>' +
      '<SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="' + origin + '/testmedia/dash/separate-' + id +
      '-init.m4s" media="' + origin + '/testmedia/dash/separate-' + id + '-$Number%05d$.m4s"/></Representation></AdaptationSet>';
    const head = ended
      ? 'type="static" mediaPresentationDuration="PT70S" minBufferTime="PT2S"'
      : 'type="dynamic" availabilityStartTime="' + new Date(probe.epoch - 6000).toISOString() + '" publishTime="' +
        new Date().toISOString() + '" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT30S" minBufferTime="PT2S"';
    return '<?xml version="1.0" encoding="utf-8"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" ' + head + '>' +
      '<Period id="0" start="PT0S">' +
      set("video", "0", 'mimeType="video/mp4" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180"') +
      set("audio", "2", 'mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="96000" audioSamplingRate="48000"') +
      '</Period></MPD>\n';
  };

  const periods = (names, live) => {
    const set = (name, type, id, attributes) =>
      '<AdaptationSet id="' + id + '" contentType="' + type + '"' + (type === "audio" ? ' lang="en"' : '') + '>' +
      '<Representation id="' + id + '" ' + attributes + '>' +
      '<SegmentTemplate timescale="1000000" duration="2000000" startNumber="1" media="/testmedia/dash/period-' + name +
      '-$RepresentationID$-$Number$.m4s" initialization="/testmedia/dash/period-' + name + '-$RepresentationID$-init.m4s"/>' +
      '</Representation></AdaptationSet>';
    const head = live
      ? 'type="dynamic" availabilityStartTime="' + new Date(probe.epoch - 16000).toISOString() +
        '" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT20S"'
      : 'type="static" mediaPresentationDuration="PT' + (names.length * 20) + 'S"';
    return '<?xml version="1.0" encoding="utf-8"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" ' + head + '>' +
      names.map((name, index) => {
        const size = name === "b" ? 'width="640" height="360"' : 'width="320" height="180"';
        return '<Period id="' + name + '" start="PT' + (index * 20) + 'S" duration="PT20S">' +
          set(name, "video", "0", 'mimeType="video/mp4" codecs="avc1.42c01e" bandwidth="300000" ' + size) +
          set(name, "audio", "1", 'mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="96000"') + '</Period>';
      }).join("") + '</MPD>\n';
  };

  return (request, response) => {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Headers", "*");
    response.setHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Date");
    if (request.method === "OPTIONS") {
      response.writeHead(204);
      response.end();
      return true;
    }
    const url = request.url;
    const send = (type, body) => {
      response.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" });
      response.end(body);
      return true;
    };
    const keep = (clip, range = null, marked = null) =>
      probe.asked.push({ clip, host: request.headers.host, range, marked, at: Date.now() - probe.epoch });
    if (url.startsWith("/testmedia/")) {
      keep(url.slice("/testmedia/".length), request.headers.range || null, request.headers["x-kite-test"] || null);
    }
    if (!url.startsWith("/kite-probe/")) return false;
    const parsed = new URL(url, "http://probe");
    const name = parsed.pathname.slice("/kite-probe/".length);
    if (name === "reset") {
      probe.asked = [];
      probe.manifests = 0;
      probe.periodFetches = 0;
      probe.epoch = Date.now();
      return send("text/plain", "ok");
    }
    if (name === "asked") return send("application/json", JSON.stringify({ asked: probe.asked, manifests: probe.manifests }));
    if (name === "live.m3u8") return send("application/vnd.apple.mpegurl", liveHls());
    if (name === "live.mpd") {
      probe.manifests++;
      keep("live.mpd", null, request.headers["x-kite-test"] || null);
      return send("application/dash+xml", liveDash(parsed.searchParams.get("origin") || "", parsed.searchParams.get("ended") === "1"));
    }
    if (name === "periods.mpd") return send("application/dash+xml", periods(["a", "b", "c"], false));
    if (name === "live-periods.mpd") {
      keep(name);
      return send("application/dash+xml", periods(++probe.periodFetches > 1 ? ["a", "b"] : ["a"], true));
    }
    if (name.startsWith("moved/")) {
      keep(name);
      response.writeHead(302, { Location: "/testmedia/" + name.slice("moved/".length) });
      response.end();
      return true;
    }
    response.writeHead(404);
    response.end();
    return true;
  };
}
