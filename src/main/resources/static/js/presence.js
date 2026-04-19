// YAC - Presence heartbeat and idle detection

(function () {
  'use strict';

  var HEARTBEAT_INTERVAL = 10000; // 10 seconds per spec
  var ACTIVE_THRESHOLD = 2000;    // cursor moved within last 2 seconds = active
  var lastCursorMove = 0;
  var heartbeatTimer = null;
  var presenceSubscriptions = {};

  function isActive() {
    return (Date.now() - lastCursorMove) <= ACTIVE_THRESHOLD;
  }

  function recordCursorActivity() {
    lastCursorMove = Date.now();
  }

  function sendHeartbeat() {
    var client = window.YAC && window.YAC.stomp && window.YAC.stomp.getClient();
    if (client && client.connected) {
      client.publish({
        destination: '/app/presence.heartbeat',
        body: JSON.stringify({
          active: isActive(),
          timestamp: new Date().toISOString()
        })
      });
    }
  }

  function startHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
    }
    heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL);
    // Send an initial heartbeat shortly after connect
    setTimeout(sendHeartbeat, 1000);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  function subscribeToFriendPresence(userId) {
    if (presenceSubscriptions[userId]) {
      return; // already subscribed
    }
    var client = window.YAC && window.YAC.stomp && window.YAC.stomp.getClient();
    if (client && client.connected) {
      presenceSubscriptions[userId] = client.subscribe(
        '/topic/presence.' + userId,
        function (message) {
          var update = JSON.parse(message.body);
          updatePresenceDot(update);
        }
      );
    }
  }

  function updatePresenceDot(update) {
    // Update all presence dots for this user (member list + contact list)
    var dots = document.querySelectorAll('.presence-dot[data-user-id="' + update.user_id + '"]');
    dots.forEach(function (dot) {
      dot.classList.remove('bg-success', 'bg-warning', 'bg-secondary');
      switch (update.status) {
        case 'ONLINE':
          dot.classList.add('bg-success');
          break;
        case 'AFK':
          dot.classList.add('bg-warning');
          break;
        default:
          dot.classList.add('bg-secondary');
          break;
      }
    });
  }

  function subscribeToAllVisibleUsers() {
    // Subscribe to presence for all users visible in the member list and contact list
    var dots = document.querySelectorAll('.presence-dot[data-user-id]');
    dots.forEach(function (dot) {
      var userId = dot.getAttribute('data-user-id');
      if (userId) {
        subscribeToFriendPresence(userId);
      }
    });
  }

  // Track cursor movement events per tab
  document.addEventListener('mousemove', recordCursorActivity);
  document.addEventListener('keydown', recordCursorActivity);

  // Start heartbeat when page loads (after STOMP connects)
  document.addEventListener('DOMContentLoaded', function () {
    // Delay to let STOMP connect first
    setTimeout(function () {
      startHeartbeat();
      subscribeToAllVisibleUsers();
    }, 2000);
  });

  window.addEventListener('beforeunload', stopHeartbeat);

  // Expose for global use
  window.YAC = window.YAC || {};
  window.YAC.presence = {
    startHeartbeat: startHeartbeat,
    stopHeartbeat: stopHeartbeat,
    isActive: isActive,
    subscribeToFriendPresence: subscribeToFriendPresence,
    subscribeToAllVisibleUsers: subscribeToAllVisibleUsers,
    updatePresenceDot: updatePresenceDot
  };
})();
