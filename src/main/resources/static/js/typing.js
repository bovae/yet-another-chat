// YAC - Typing indicators: send with debounce + receive and display

(function () {
  'use strict';

  var lastTypingSent = 0;
  var DEBOUNCE_MS = 2000;
  var TIMEOUT_MS = 3000;
  var typingUsers = {}; // { username: timeoutId }

  // --- Send typing event with debounce ---

  function sendTypingEvent() {
    var now = Date.now();
    if (now - lastTypingSent < DEBOUNCE_MS) {
      return;
    }

    var roomId = window.YAC_ROOM ? window.YAC_ROOM.id : null;
    if (!roomId) {
      return;
    }

    var client = window.YAC && window.YAC.stomp ? window.YAC.stomp.getClient() : null;
    if (!client || !client.connected) {
      return;
    }

    lastTypingSent = now;
    client.publish({
      destination: '/app/typing',
      body: JSON.stringify({ room_id: roomId })
    });
  }

  function attachInputListener() {
    var textarea = document.getElementById('message-textarea');
    if (textarea) {
      textarea.addEventListener('input', sendTypingEvent);
    }
  }

  // --- Receive and display typing indicators ---

  function onEvent(event) {
    // Gracefully handle missing YAC_USER (e.g. on index.html)
    var currentUserId = window.YAC_USER ? window.YAC_USER.id : null;
    var eventUserId = event.user_id || event.userId;
    var eventUsername = event.username;

    // Ignore own typing events
    if (currentUserId && eventUserId && String(eventUserId) === String(currentUserId)) {
      return;
    }

    if (!eventUsername) {
      return;
    }

    // Clear existing timeout for this user if present
    if (typingUsers[eventUsername]) {
      clearTimeout(typingUsers[eventUsername]);
    }

    // Set a new timeout to remove the user after 3s
    typingUsers[eventUsername] = setTimeout(function () {
      delete typingUsers[eventUsername];
      renderTypingIndicator();
    }, TIMEOUT_MS);

    renderTypingIndicator();
  }

  function renderTypingIndicator() {
    var el = document.getElementById('typing-indicator');
    if (!el) {
      return;
    }

    var names = Object.keys(typingUsers);

    if (names.length === 0) {
      el.textContent = '';
      return;
    }

    var text;
    if (names.length === 1) {
      text = names[0] + ' is typing...';
    } else if (names.length === 2) {
      text = names[0] + ' and ' + names[1] + ' are typing...';
    } else {
      var others = names.length - 2;
      text = names[0] + ', ' + names[1] + ', and ' + others + (others === 1 ? ' other' : ' others')
        + ' are typing...';
    }

    el.textContent = text;
  }

  // --- Init ---

  function init() {
    attachInputListener();
    console.log('[Typing] Typing indicators initialized');
  }

  // Expose on window.YAC
  window.YAC = window.YAC || {};
  window.YAC.typing = {
    onEvent: onEvent
  };

  document.addEventListener('DOMContentLoaded', init);
})();
