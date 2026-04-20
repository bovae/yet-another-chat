// YAC - STOMP WebSocket client

(function () {
  'use strict';

  var stompClient = null;
  var lastWatermark = null;
  var roomSubscription = null;
  var roomEventsSubscription = null;
  var errorSubscription = null;
  var notificationSubscription = null;

  function getCsrfToken() {
    var meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.content : '';
  }

  function getCsrfHeader() {
    var meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.content : '';
  }

  function getRoomId() {
    return window.YAC_ROOM ? window.YAC_ROOM.id : null;
  }

  function connect() {
    if (typeof StompJs === 'undefined') {
      console.warn('[STOMP] StompJs not loaded, skipping WebSocket connection');
      return;
    }

    var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

    stompClient = new StompJs.Client({
      brokerURL: protocol + '//' + window.location.host + '/ws',
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
      subscribeToChannels();
      fetchMissedMessages();
    };

    stompClient.onStompError = function (frame) {
      console.error('[STOMP] Error:', frame.headers['message']);
    };

    stompClient.activate();
  }

  function subscribeToChannels() {
    var roomId = getRoomId();

    // Subscribe to room messages
    if (roomId) {
      roomSubscription = stompClient.subscribe('/topic/room.' + roomId, function (message) {
        var msg = JSON.parse(message.body);
        handleIncomingMessage(msg);
      });

      // Subscribe to room events (typing indicators, etc.)
      roomEventsSubscription = stompClient.subscribe('/topic/room.' + roomId + '.events', function (message) {
        var event = JSON.parse(message.body);
        if (window.YAC && window.YAC.typing && window.YAC.typing.onEvent) {
          window.YAC.typing.onEvent(event);
        }
      });
    }

    // Subscribe to user error queue
    errorSubscription = stompClient.subscribe('/user/queue/errors', function (message) {
      var error = JSON.parse(message.body);
      console.error('[STOMP] Server error:', error);
      handleError(error);
    });

    // Subscribe to user notification queue
    notificationSubscription = stompClient.subscribe('/user/queue/notifications', function (message) {
      var notification = JSON.parse(message.body);
      handleNotification(notification);
    });

    // Trigger presence subscriptions now that STOMP is connected
    if (window.YAC && window.YAC.presence && window.YAC.presence.subscribeToAllVisibleUsers) {
      window.YAC.presence.subscribeToAllVisibleUsers();
    }
  }

  function handleIncomingMessage(msg) {
    // Track watermark for gap detection
    if (msg.watermark) {
      lastWatermark = msg.watermark;
    }

    // Delegate to app.js for DOM rendering
    if (window.YAC && window.YAC.app && window.YAC.app.onNewMessage) {
      window.YAC.app.onNewMessage(msg);
    }
  }

  function handleError(error) {
    var messageList = document.getElementById('message-list');
    if (messageList) {
      var errorDiv = document.createElement('div');
      errorDiv.className = 'alert alert-danger alert-dismissible fade show mx-3 my-1 py-1 px-2 small';
      errorDiv.setAttribute('role', 'alert');
      errorDiv.textContent = error.message || 'An error occurred';
      var closeBtn = document.createElement('button');
      closeBtn.type = 'button';
      closeBtn.className = 'btn-close btn-sm';
      closeBtn.setAttribute('data-bs-dismiss', 'alert');
      closeBtn.setAttribute('aria-label', 'Close');
      errorDiv.appendChild(closeBtn);
      messageList.appendChild(errorDiv);
    }
  }

  function handleNotification(notification) {
    if (window.YAC && window.YAC.app && window.YAC.app.onNotification) {
      window.YAC.app.onNotification(notification);
    }
  }

  function fetchMissedMessages() {
    var roomId = getRoomId();
    if (!roomId || !lastWatermark) {
      // First connect or no messages yet — initialize watermark from DOM
      initWatermarkFromDom();
      return;
    }

    // Fetch messages after our last known watermark
    var url = '/api/rooms/' + roomId + '/messages?cursor=' + lastWatermark + '&size=100';
    var headers = { 'Accept': 'application/json' };
    var csrfHeader = getCsrfHeader();
    if (csrfHeader) {
      headers[csrfHeader] = getCsrfToken();
    }
    fetch(url, { headers: headers })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('Failed to fetch missed messages: ' + response.status);
      }
      return response.json();
    })
    .then(function (page) {
      if (page.messages && page.messages.length > 0) {
        page.messages.forEach(function (msg) {
          handleIncomingMessage(msg);
        });
      }
    })
    .catch(function (err) {
      console.error('[STOMP] Error fetching missed messages:', err);
    });
  }

  function initWatermarkFromDom() {
    var items = document.querySelectorAll('.message-item[data-watermark]');
    if (items.length > 0) {
      var last = items[items.length - 1];
      var wm = parseInt(last.getAttribute('data-watermark'), 10);
      if (!isNaN(wm)) {
        lastWatermark = wm;
      }
    }
  }

  function disconnect() {
    if (stompClient !== null) {
      stompClient.deactivate();
      console.log('[STOMP] Disconnected');
    }
  }

  function sendMessage(roomId, content, replyToId) {
    if (stompClient && stompClient.connected) {
      var payload = {
        room_id: roomId,
        content: content
      };
      if (replyToId) {
        payload.reply_to_id = replyToId;
      }
      stompClient.publish({
        destination: '/app/chat.send',
        body: JSON.stringify(payload)
      });
    }
  }

  // Expose for global use
  window.YAC = window.YAC || {};
  window.YAC.stomp = {
    connect: connect,
    disconnect: disconnect,
    sendMessage: sendMessage,
    getClient: function () { return stompClient; },
    getLastWatermark: function () { return lastWatermark; }
  };

  // Auto-connect on page load
  document.addEventListener('DOMContentLoaded', function () {
    connect();
    initWatermarkFromDom();
  });
  window.addEventListener('beforeunload', disconnect);
})();
