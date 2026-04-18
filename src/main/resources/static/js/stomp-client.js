// YAC - STOMP WebSocket client

(function () {
  'use strict';

  var stompClient = null;

  function connect() {
    if (typeof StompJs === 'undefined') {
      console.warn('StompJs not loaded, skipping WebSocket connection');
      return;
    }

    stompClient = new StompJs.Client({
      brokerURL: 'ws://' + window.location.host + '/ws',
      connectHeaders: {},
      debug: function (str) {
        console.log('[STOMP] ' + str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000
    });

    stompClient.onConnect = function () {
      console.log('[STOMP] Connected');

      // Subscribe to general chat topic (echo test)
      stompClient.subscribe('/topic/chat', function (message) {
        console.log('[STOMP] Received:', JSON.parse(message.body));
      });

      // Subscribe to presence updates
      stompClient.subscribe('/topic/presence', function (message) {
        console.log('[STOMP] Presence update:', JSON.parse(message.body));
      });
    };

    stompClient.onStompError = function (frame) {
      console.error('[STOMP] Error:', frame.headers['message']);
    };

    stompClient.activate();
  }

  function disconnect() {
    if (stompClient !== null) {
      stompClient.deactivate();
      console.log('[STOMP] Disconnected');
    }
  }

  function sendMessage(content) {
    if (stompClient && stompClient.connected) {
      stompClient.publish({
        destination: '/app/chat.send',
        body: JSON.stringify({ content: content })
      });
    }
  }

  // Expose for global use
  window.YAC = window.YAC || {};
  window.YAC.stomp = {
    connect: connect,
    disconnect: disconnect,
    sendMessage: sendMessage,
    getClient: function () { return stompClient; }
  };

  // Auto-connect on page load
  document.addEventListener('DOMContentLoaded', connect);
  window.addEventListener('beforeunload', disconnect);
})();
