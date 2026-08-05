/* Mail Search — the whole UI. No frameworks, no network beyond this app. */
(function () {
  "use strict";

  var MARK_START = "\u0002";
  var MARK_END = "\u0003";
  var PAGE_SIZE = 25;

  var el = {};
  [
    "signin", "signin-btn", "signin-error", "devicecode", "user-code", "verify-link",
    "copy-code", "login-hint", "search", "q", "clear", "summary", "results", "empty",
    "more", "facets", "reader", "reader-subject", "reader-meta", "reader-body",
    "reader-open", "reader-close", "account", "sync-btn", "sync-bar", "sync-text",
    "sync-cancel", "menu", "menu-btn", "include-all", "sort",
    "lock", "lock-form", "lock-error", "passcode"
  ].forEach(function (id) { el[id] = document.getElementById(id); });

  var state = {
    query: "",
    offset: 0,
    total: 0,
    results: [],
    active: -1,
    signedIn: false,
    locked: false,
    source: "graph",
    syncing: false,
    seq: 0,
    loginTimer: null,
    statusTimer: null
  };

  /* -- helpers ---------------------------------------------------------- */
  function api(path, options) {
    options = options || {};
    var init = {
      method: options.method || "GET",
      headers: { "X-Mailsearch": "1" },
      cache: "no-store"
    };
    if (options.body) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.body);
    }
    if (options.signal) init.signal = options.signal;
    return fetch(path, init).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        if (!response.ok) {
          var error = new Error(data.error || ("Request failed (" + response.status + ")"));
          error.status = response.status;
          error.needsLogin = !!data.needs_login;
          error.needsPasscode = !!data.needs_passcode;
          throw error;
        }
        return data;
      });
    });
  }

  function escapeHtml(text) {
    return String(text == null ? "" : text)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  // Snippets arrive with control-character markers around matches. Escape
  // first, then turn the markers into <mark> — mail text can never inject HTML.
  function highlight(text) {
    return escapeHtml(text).split(MARK_START).join("<mark>").split(MARK_END).join("</mark>");
  }

  function formatDate(iso) {
    if (!iso) return "";
    var when = new Date(iso);
    if (isNaN(when.getTime())) return iso;
    var now = new Date();
    var sameDay = when.toDateString() === now.toDateString();
    if (sameDay) return when.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    if (when.getFullYear() === now.getFullYear()) {
      return when.toLocaleDateString([], { month: "short", day: "numeric" });
    }
    return when.toLocaleDateString([], { year: "numeric", month: "short", day: "numeric" });
  }

  function plural(count, word) {
    var suffix = count === 1 ? "" : (/(?:ch|sh|s|x|z)$/.test(word) ? "es" : "s");
    return count.toLocaleString() + " " + word + suffix;
  }

  /* -- status ----------------------------------------------------------- */
  function refreshStatus() {
    return api("/api/status").then(function (status) {
      state.locked = false;
      state.signedIn = status.signed_in;
      state.source = status.source || "graph";
      show(el["lock"], false);
      el["account"].textContent = status.account || "";
      show(el["search"], status.signed_in);
      show(el["signin"], !status.signed_in);
      // Syncing and the account menu mean nothing until there is an account.
      show(el["sync-btn"], status.signed_in);
      show(el["menu-btn"], status.signed_in);
      // Reading Thunderbird's files involves no Outlook session to sign out of.
      var signOut = el["menu"].querySelector('[data-action="logout"]');
      if (signOut) show(signOut, state.source !== "thunderbird");

      var sync = status.sync || {};
      state.syncing = !!sync.running;
      renderSync(sync, status.index || {});

      if (status.signed_in && !state.query) renderIdle(status.index || {});
      scheduleStatus();
      return status;
    }).catch(function (error) {
      if (error && error.needsPasscode) return showLock("");
      scheduleStatus();
    });
  }

  /* -- passcode --------------------------------------------------------- */
  function showLock(message) {
    state.locked = true;
    clearTimeout(state.statusTimer);
    show(el["lock"], true);
    show(el["search"], false);
    show(el["signin"], false);
    show(el["sync-btn"], false);
    show(el["menu-btn"], false);
    show(el["sync-bar"], false);
    el["lock-error"].textContent = message || "";
    show(el["lock-error"], !!message);
    el["passcode"].focus();
  }

  function unlock(event) {
    event.preventDefault();
    var passcode = el["passcode"].value;
    if (!passcode) return;
    show(el["lock-error"], false);
    api("/api/unlock", { method: "POST", body: { passcode: passcode } })
      .then(function () {
        el["passcode"].value = "";
        state.locked = false;
        return refreshStatus();
      })
      .then(function () { if (!state.locked) el["q"].focus(); })
      .catch(function (error) {
        el["lock-error"].textContent = error.message;
        show(el["lock-error"], true);
        el["passcode"].select();
      });
  }

  function scheduleStatus() {
    clearTimeout(state.statusTimer);
    state.statusTimer = setTimeout(refreshStatus, state.syncing ? 1200 : 15000);
  }

  function renderSync(sync, index) {
    var running = !!sync.running;
    show(el["sync-bar"], running || (sync.error && sync.phase === "failed"));
    el["sync-btn"].disabled = running;
    el["sync-btn"].textContent = running ? "Syncing…" : "Sync";
    show(el["sync-cancel"], running);

    if (sync.error) {
      el["sync-text"].textContent = "Sync problem: " + sync.error;
      return;
    }
    if (!running) return;
    var parts = [];
    if (sync.folders_total) parts.push("folder " + sync.folders_done + " of " + sync.folders_total);
    if (sync.folder) parts.push(sync.folder);
    parts.push(plural(sync.indexed || 0, "message") + " indexed");
    el["sync-text"].textContent = parts.join(" · ");
    if (!state.query && index) renderIdle(index);
  }

  function renderIdle(index) {
    var count = index.messages || 0;
    el["summary"].textContent = count
      ? plural(count, "message") + " indexed" + (index.last_sync ? " · last sync " + index.last_sync.replace("T", " ") : "")
      : "";
    if (state.query) return;
    el["results"].innerHTML = "";
    show(el["more"], false);
    show(el["facets"], false);
    show(el["empty"], true);
    el["empty"].innerHTML = count
      ? "Type anything you remember — a word, a name, a phrase.<br><span class='small'>Try <code>from:name</code>, <code>\"exact phrase\"</code>, or <code>has:attachment</code>.</span>"
      : (state.syncing
          ? "Reading your mail… you can start searching as soon as results appear."
          : (state.source === "thunderbird"
              ? "Nothing indexed yet. Press <b>Sync</b> to read the mail Thunderbird has stored."
              : "Nothing indexed yet. Press <b>Sync</b> to download your mail."));
  }

  function show(node, visible) {
    if (node) node.hidden = !visible;
  }

  /* -- sign-in ---------------------------------------------------------- */
  function startLogin() {
    el["signin-btn"].disabled = true;
    show(el["signin-error"], false);
    api("/api/login", { method: "POST" }).then(function (flow) {
      show(el["devicecode"], true);
      el["user-code"].textContent = flow.user_code || "";
      var uri = flow.verification_uri || "https://microsoft.com/devicelogin";
      el["verify-link"].href = uri;
      el["verify-link"].textContent = uri.replace(/^https?:\/\//, "");
      el["login-hint"].textContent = "Waiting for approval…";
      pollLogin();
    }).catch(function (error) {
      el["signin-btn"].disabled = false;
      el["signin-error"].textContent = error.message;
      show(el["signin-error"], true);
    });
  }

  function pollLogin() {
    clearTimeout(state.loginTimer);
    state.loginTimer = setTimeout(function () {
      api("/api/login").then(function (status) {
        if (status.state === "complete") {
          show(el["devicecode"], false);
          el["signin-btn"].disabled = false;
          refreshStatus().then(function () { el["q"].focus(); });
          return;
        }
        if (status.state === "error") {
          el["signin-btn"].disabled = false;
          show(el["devicecode"], false);
          el["signin-error"].textContent = status.error || "Sign-in failed.";
          show(el["signin-error"], true);
          return;
        }
        pollLogin();
      }).catch(pollLogin);
    }, 2500);
  }

  /* -- search ----------------------------------------------------------- */
  var debounce = null;
  function onType() {
    var value = el["q"].value;
    show(el["clear"], value.length > 0);
    clearTimeout(debounce);
    debounce = setTimeout(function () { runSearch(value, 0); }, 160);
  }

  function runSearch(text, offset) {
    if (state.locked) return;
    state.query = text;
    state.offset = offset;
    if (!text.trim()) {
      state.results = [];
      closeReader();
      refreshStatus();
      return;
    }

    var mine = ++state.seq;
    var params = new URLSearchParams({
      q: text,
      limit: String(PAGE_SIZE),
      offset: String(offset),
      sort: el["sort"].value,
      facets: offset === 0 ? "1" : "0"
    });
    if (el["include-all"].checked) params.set("all", "1");

    api("/api/search?" + params.toString()).then(function (payload) {
      if (mine !== state.seq) return;  // a newer keystroke already won
      state.total = payload.total;
      state.results = offset === 0 ? payload.results : state.results.concat(payload.results);
      renderResults(payload, offset === 0);
    }).catch(function (error) {
      if (mine !== state.seq) return;
      if (error.needsPasscode) return showLock("");
      show(el["empty"], true);
      el["empty"].textContent = error.message;
      if (error.needsLogin) refreshStatus();
    });
  }

  function renderResults(payload, replace) {
    if (replace) {
      el["results"].innerHTML = "";
      state.active = -1;
      closeReader();
    }
    var warnings = (payload.query && payload.query.warnings) || [];
    el["summary"].textContent = payload.total
      ? plural(payload.total, "match") + " in " + (payload.took_ms || 0) + " ms"
        + (warnings.length ? " · " + warnings.join("; ") : "")
      : "";

    show(el["empty"], payload.total === 0);
    if (payload.total === 0) {
      el["empty"].innerHTML = "No mail matches <b>" + escapeHtml(payload.query.raw) + "</b>."
        + "<br><span class='small'>Try fewer words, or tick “junk &amp; deleted”.</span>";
    }

    var start = replace ? 0 : state.results.length - payload.results.length;
    payload.results.forEach(function (row, index) {
      el["results"].appendChild(renderHit(row, start + index));
    });
    show(el["more"], state.results.length < payload.total);
    el["more"].textContent = "Show more (" + (payload.total - state.results.length).toLocaleString() + " left)";

    if (replace) renderFacets(payload.facets);
  }

  function renderHit(row, index) {
    var node = document.createElement("button");
    node.type = "button";
    node.className = "hit" + (row.is_read ? "" : " unread");
    node.dataset.index = String(index);

    var sender = row.from_name || row.from_address || "(unknown sender)";
    var snippet = row.body_snippet || "";
    var body = snippet ? highlight(snippet) : escapeHtml(row.preview || "");

    node.innerHTML =
      '<div class="hit-top">' +
        '<span class="hit-subject">' + (row.subject_snippet ? highlight(row.subject_snippet) : escapeHtml(row.subject || "(no subject)")) + "</span>" +
        '<span class="hit-date">' + escapeHtml(formatDate(row.received_at)) + "</span>" +
      "</div>" +
      '<div class="hit-meta">' +
        "<span>" + escapeHtml(sender) + "</span>" +
        (row.from_address && row.from_name ? '<span class="muted">' + escapeHtml(row.from_address) + "</span>" : "") +
        (row.has_attachments ? "<span>📎</span>" : "") +
        '<span class="folder">' + escapeHtml(row.folder_name || "") + "</span>" +
      "</div>" +
      (body ? '<div class="hit-snippet">' + body + "</div>" : "");

    node.addEventListener("click", function () { select(index); });
    return node;
  }

  function renderFacets(facets) {
    if (!facets || (!facets.senders.length && !facets.folders.length)) {
      show(el["facets"], false);
      return;
    }
    el["facets"].innerHTML = "";
    facets.senders.slice(0, 5).forEach(function (sender) {
      addChip(sender.name || sender.address, sender.count, "from:" + quoteIfNeeded(sender.address || sender.name));
    });
    facets.folders.slice(0, 4).forEach(function (folder) {
      addChip(folder.name, folder.count, "folder:" + quoteIfNeeded(folder.name));
    });
    show(el["facets"], true);
  }

  function addChip(label, count, term) {
    var chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip";
    chip.innerHTML = escapeHtml(label) + " <b>" + count + "</b>";
    chip.title = "Add " + term + " to the search";
    chip.addEventListener("click", function () {
      el["q"].value = (el["q"].value.trim() + " " + term).trim() + " ";
      el["q"].focus();
      runSearch(el["q"].value, 0);
    });
    el["facets"].appendChild(chip);
  }

  function quoteIfNeeded(value) {
    value = value || "";
    return /\s/.test(value) ? '"' + value.replace(/"/g, "") + '"' : value;
  }

  /* -- reading pane ------------------------------------------------------ */
  function select(index) {
    if (index < 0 || index >= state.results.length) return;
    state.active = index;
    Array.prototype.forEach.call(el["results"].children, function (node, position) {
      node.classList.toggle("active", position === index);
    });
    var node = el["results"].children[index];
    if (node) node.scrollIntoView({ block: "nearest" });
    openMessage(state.results[index]);
  }

  function openMessage(row) {
    show(el["reader"], true);
    el["reader-subject"].textContent = row.subject || "(no subject)";
    el["reader-meta"].innerHTML =
      "<div><b>From:</b> " + escapeHtml([row.from_name, row.from_address].filter(Boolean).join(" · ")) + "</div>" +
      (row.to_recipients ? "<div><b>To:</b> " + escapeHtml(row.to_recipients) + "</div>" : "") +
      "<div><b>Date:</b> " + escapeHtml(new Date(row.received_at).toLocaleString()) + "</div>" +
      "<div><b>Folder:</b> " + escapeHtml(row.folder_name || "") + "</div>";
    el["reader-open"].href = row.web_link || "#";
    show(el["reader-open"], !!row.web_link);
    el["reader-body"].textContent = "Loading…";

    api("/api/message?id=" + encodeURIComponent(row.id)).then(function (message) {
      if (state.results[state.active] && state.results[state.active].id !== row.id) return;
      el["reader-body"].textContent = message.body || message.preview || "(this message has no text body)";
    }).catch(function (error) {
      el["reader-body"].textContent = error.message;
    });
  }

  function closeReader() {
    show(el["reader"], false);
    state.active = -1;
    Array.prototype.forEach.call(el["results"].children, function (node) {
      node.classList.remove("active");
    });
  }

  /* -- actions ----------------------------------------------------------- */
  function startSync(full) {
    el["sync-btn"].disabled = true;
    api("/api/sync", { method: "POST", body: { full: !!full } })
      .then(function () { state.syncing = true; refreshStatus(); })
      .catch(function (error) {
        el["sync-btn"].disabled = false;
        if (error.needsLogin) refreshStatus();
        else window.alert(error.message);
      });
  }

  function menuAction(action) {
    show(el["menu"], false);
    if (action === "full-sync") {
      if (window.confirm("Re-read every folder from the beginning? This can take a while.")) {
        startSync(true);
      }
    } else if (action === "reset") {
      if (window.confirm("Erase the local index? Your mail in Outlook is not touched.")) {
        api("/api/reset", { method: "POST" }).then(function () {
          state.query = "";
          el["q"].value = "";
          refreshStatus();
        }).catch(function (error) { window.alert(error.message); });
      }
    } else if (action === "logout") {
      api("/api/logout", { method: "POST" }).then(function () {
        state.query = "";
        el["q"].value = "";
        refreshStatus();
      });
    }
  }

  /* -- wiring ------------------------------------------------------------ */
  el["lock-form"].addEventListener("submit", unlock);
  el["signin-btn"].addEventListener("click", startLogin);
  el["copy-code"].addEventListener("click", function () {
    var code = el["user-code"].textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(code);
    el["copy-code"].textContent = "Copied";
    setTimeout(function () { el["copy-code"].textContent = "Copy"; }, 1500);
  });

  el["q"].addEventListener("input", onType);
  el["clear"].addEventListener("click", function () {
    el["q"].value = "";
    show(el["clear"], false);
    runSearch("", 0);
    el["q"].focus();
  });
  el["sort"].addEventListener("change", function () { runSearch(el["q"].value, 0); });
  el["include-all"].addEventListener("change", function () { runSearch(el["q"].value, 0); });
  el["more"].addEventListener("click", function () {
    runSearch(state.query, state.results.length);
  });

  el["reader-close"].addEventListener("click", closeReader);
  el["sync-btn"].addEventListener("click", function () { startSync(false); });
  el["sync-cancel"].addEventListener("click", function () {
    api("/api/sync/cancel", { method: "POST" }).then(refreshStatus);
  });
  el["menu-btn"].addEventListener("click", function (event) {
    event.stopPropagation();
    show(el["menu"], el["menu"].hidden);
  });
  document.addEventListener("click", function () { show(el["menu"], false); });
  el["menu"].addEventListener("click", function (event) {
    var action = event.target && event.target.dataset ? event.target.dataset.action : "";
    if (action) menuAction(action);
  });

  document.addEventListener("keydown", function (event) {
    var typing = document.activeElement === el["q"];
    if (event.key === "/" && !typing) {
      event.preventDefault();
      el["q"].focus();
      el["q"].select();
      return;
    }
    if (event.key === "Escape") {
      if (!el["reader"].hidden) closeReader();
      else if (typing) { el["q"].value = ""; runSearch("", 0); }
      return;
    }
    if (!state.results.length) return;
    if (event.key === "ArrowDown" || (event.key === "j" && !typing)) {
      event.preventDefault();
      select(Math.min(state.active + 1, state.results.length - 1));
    } else if (event.key === "ArrowUp" || (event.key === "k" && !typing)) {
      event.preventDefault();
      select(Math.max(state.active - 1, 0));
    } else if (event.key === "Enter" && typing && state.active < 0) {
      select(0);
    }
  });

  refreshStatus().then(function () {
    if (state.signedIn) el["q"].focus();
  });
})();
