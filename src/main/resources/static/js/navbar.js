// YAC - Navbar aggregate notification badge (R3-10)

(function () {
  'use strict';

  var refreshTimer = null;

  function renderBadge(total) {
    var badge = document.getElementById('navbar-notif-badge');
    if (!badge) {
      return;
    }
    if (total > 0) {
      badge.textContent = total > 99 ? '99+' : total;
      badge.classList.remove('d-none');
    } else {
      badge.classList.add('d-none');
    }
  }

  // Seed (and re-seed) the badge from the authoritative summary endpoint.
  function fetchSummary() {
    fetch('/api/notifications/summary', { headers: { 'Accept': 'application/json' } })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('summary HTTP ' + response.status);
        }
        return response.json();
      })
      .then(function (summary) {
        var total = (summary.unread_total || 0)
          + (summary.pending_friend_requests || 0)
          + (summary.pending_invitations || 0);
        renderBadge(total);
      })
      .catch(function (err) {
        console.warn('[Navbar] Failed to load notification summary:', err);
      });
  }

  // Debounced re-fetch so a burst of live events triggers at most one request.
  function refresh() {
    if (refreshTimer) {
      return;
    }
    refreshTimer = setTimeout(function () {
      refreshTimer = null;
      fetchSummary();
    }, 500);
  }

  window.YAC = window.YAC || {};
  window.YAC.navbar = { refresh: refresh, reload: fetchSummary };

  document.addEventListener('DOMContentLoaded', fetchSummary);
})();
