// YAC - Presence heartbeat and idle detection with multi-tab AFK coordination

(function () {
  'use strict';

  var HEARTBEAT_INTERVAL = 10000; // 10 seconds per spec
  var ACTIVE_THRESHOLD = 2000;    // cursor moved within last 2 seconds = active
  var IDLE_THRESHOLD = 60000;     // all tabs idle for 60s = AFK
  var CHANNEL_NAME = 'yac-presence';

  var heartbeatTimer = null;
  var presenceSubscriptions = {};

  // Cross-tab coordination state
  var myTabId = Math.random().toString(36).substring(2) + Date.now().toString(36);
  var lastActivityTimestamps = {}; // tabId → timestamp
  var channel = null;
  var useLocalStorageFallback = false;

  // Initialize own tab's timestamp
  lastActivityTimestamps[myTabId] = 0;

  // --- BroadcastChannel setup with localStorage fallback ---
  if (typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = function (event) {
      handleCrossTabMessage(event.data);
    };
  } else {
    useLocalStorageFallback = true;
    window.addEventListener('storage', function (event) {
      if (event.key === CHANNEL_NAME && event.newValue) {
        try {
          handleCrossTabMessage(JSON.parse(event.newValue));
        } catch (e) {
          // ignore malformed messages
        }
      }
    });
  }

  function broadcastMessage(msg) {
    if (channel) {
      channel.postMessage(msg);
    } else if (useLocalStorageFallback) {
      try {
        localStorage.setItem(CHANNEL_NAME, JSON.stringify(msg));
        // Remove immediately so subsequent writes trigger storage events
        localStorage.removeItem(CHANNEL_NAME);
      } catch (e) {
        // localStorage may be unavailable in some contexts
      }
    }
  }

  function handleCrossTabMessage(data) {
    if (!data || !data.tabId || data.tabId === myTabId) {
      return;
    }
    if (data.type === 'active') {
      lastActivityTimestamps[data.tabId] = data.timestamp || Date.now();
    } else if (data.type === 'closing') {
      delete lastActivityTimestamps[data.tabId];
    }
  }

  function recordCursorActivity() {
    var now = Date.now();
    lastActivityTimestamps[myTabId] = now;
    broadcastMessage({ type: 'active', tabId: myTabId, timestamp: now });
  }

  function isActive() {
    var now = Date.now();
    var tabIds = Object.keys(lastActivityTimestamps);
    for (var i = 0; i < tabIds.length; i++) {
      if ((now - lastActivityTimestamps[tabIds[i]]) <= ACTIVE_THRESHOLD) {
        return true;
      }
    }
    return false;
  }

  function isAllTabsIdle60s() {
    var now = Date.now();
    var tabIds = Object.keys(lastActivityTimestamps);
    if (tabIds.length === 0) {
      return true;
    }
    for (var i = 0; i < tabIds.length; i++) {
      if ((now - lastActivityTimestamps[tabIds[i]]) < IDLE_THRESHOLD) {
        return false;
      }
    }
    return true;
  }

  function sendHeartbeat() {
    var client = window.YAC && window.YAC.stomp && window.YAC.stomp.getClient();
    if (client && client.connected) {
      client.publish({
        destination: '/app/presence.heartbeat',
        body: JSON.stringify({
          active: !isAllTabsIdle60s(),
          timestamp: new Date().toISOString()
        })
      });
    }
  }

  function sendImmediateActiveHeartbeat() {
    // Record activity for this tab and send heartbeat immediately
    lastActivityTimestamps[myTabId] = Date.now();
    broadcastMessage({ type: 'active', tabId: myTabId, timestamp: Date.now() });
    var client = window.YAC && window.YAC.stomp && window.YAC.stomp.getClient();
    if (client && client.connected) {
      client.publish({
        destination: '/app/presence.heartbeat',
        body: JSON.stringify({
          active: true,
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

  // Visibility/focus immediate heartbeat (Req 17.5)
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      sendImmediateActiveHeartbeat();
    }
  });
  window.addEventListener('focus', function () {
    sendImmediateActiveHeartbeat();
  });

  // Start heartbeat when page loads (after STOMP connects)
  document.addEventListener('DOMContentLoaded', function () {
    // Delay to let STOMP connect first
    setTimeout(function () {
      startHeartbeat();
      subscribeToAllVisibleUsers();
    }, 2000);
  });

  // Tab cleanup on close (Req 17.6)
  window.addEventListener('beforeunload', function () {
    stopHeartbeat();
    // Notify other tabs this tab is closing
    broadcastMessage({ type: 'closing', tabId: myTabId });
    // Remove own entry
    delete lastActivityTimestamps[myTabId];
    // Close BroadcastChannel if open
    if (channel) {
      channel.close();
      channel = null;
    }
  });

  // Expose for global use
  window.YAC = window.YAC || {};
  window.YAC.presence = {
    startHeartbeat: startHeartbeat,
    stopHeartbeat: stopHeartbeat,
    isActive: isActive,
    isAllTabsIdle60s: isAllTabsIdle60s,
    subscribeToFriendPresence: subscribeToFriendPresence,
    subscribeToAllVisibleUsers: subscribeToAllVisibleUsers,
    updatePresenceDot: updatePresenceDot
  };
})();
