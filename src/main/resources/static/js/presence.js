// YAC - Presence heartbeat and idle detection

(function () {
  'use strict';

  var HEARTBEAT_INTERVAL = 15000; // 15 seconds
  var IDLE_THRESHOLD = 60000;     // 1 minute
  var lastActivity = Date.now();
  var heartbeatTimer = null;

  function isIdle() {
    return (Date.now() - lastActivity) > IDLE_THRESHOLD;
  }

  function recordActivity() {
    lastActivity = Date.now();
  }

  function sendHeartbeat() {
    var client = window.YAC && window.YAC.stomp && window.YAC.stomp.getClient();
    if (client && client.connected) {
      client.publish({
        destination: '/app/presence.heartbeat',
        body: JSON.stringify({
          active: !isIdle(),
          timestamp: new Date().toISOString()
        })
      });
      console.log('[Presence] Heartbeat sent, idle:', isIdle());
    }
  }

  function startHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
    }
    heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  // Track user activity
  document.addEventListener('mousemove', recordActivity);
  document.addEventListener('keydown', recordActivity);
  document.addEventListener('click', recordActivity);
  document.addEventListener('scroll', recordActivity);
  document.addEventListener('touchstart', recordActivity);

  // Start heartbeat when page loads
  document.addEventListener('DOMContentLoaded', function () {
    // Small delay to let STOMP connect first
    setTimeout(startHeartbeat, 2000);
  });

  window.addEventListener('beforeunload', stopHeartbeat);

  // Expose for global use
  window.YAC = window.YAC || {};
  window.YAC.presence = {
    startHeartbeat: startHeartbeat,
    stopHeartbeat: stopHeartbeat,
    isIdle: isIdle
  };
})();
