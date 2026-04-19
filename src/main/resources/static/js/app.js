// YAC - Application logic: message rendering, infinite scroll, admin actions

(function () {
  'use strict';

  var isLoadingHistory = false;
  var currentRoomId = null;

  // --- Helpers ---

  function getCsrfToken() {
    var meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.content : '';
  }

  function getCsrfHeader() {
    var meta = document.querySelector('meta[name="_csrf_header"]');
    return meta ? meta.content : '';
  }

  function apiHeaders() {
    var headers = {
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    };
    var csrfHeader = getCsrfHeader();
    if (csrfHeader) {
      headers[csrfHeader] = getCsrfToken();
    }
    return headers;
  }

  function getRoomId() {
    if (currentRoomId) {
      return currentRoomId;
    }
    if (window.YAC_ROOM) {
      currentRoomId = window.YAC_ROOM.id;
    }
    return currentRoomId;
  }

  // --- Modal utility functions ---

  function showErrorModal(message) {
    var modalEl = document.getElementById('errorModal');
    if (!modalEl) {
      return;
    }
    var msgEl = document.getElementById('errorModalMessage');
    if (msgEl) {
      msgEl.textContent = message;
    }
    new bootstrap.Modal(modalEl).show();
  }

  function showConfirmModal(message, onConfirm) {
    var modalEl = document.getElementById('confirmModal');
    if (!modalEl) {
      return;
    }
    var msgEl = document.getElementById('confirmModalMessage');
    if (msgEl) {
      msgEl.textContent = message;
    }
    var confirmBtn = document.getElementById('confirmModalConfirmBtn');
    if (confirmBtn) {
      // Clone and replace to remove any previously stacked listeners
      var newBtn = confirmBtn.cloneNode(true);
      confirmBtn.parentNode.replaceChild(newBtn, confirmBtn);
      newBtn.addEventListener('click', function () {
        var modal = bootstrap.Modal.getInstance(modalEl);
        if (modal) {
          modal.hide();
        }
        onConfirm();
      });
    }
    new bootstrap.Modal(modalEl).show();
  }

  // Expose modal utilities on window for inline scripts and other modules
  window.showErrorModal = showErrorModal;
  window.showConfirmModal = showConfirmModal;

  // --- Message DOM creation ---

  function createMessageElement(msg) {
    var item = document.createElement('div');
    item.className = 'message-item d-flex mb-3';
    item.setAttribute('data-message-id', msg.id);
    item.setAttribute('data-watermark', msg.watermark);

    // Avatar
    var avatarWrap = document.createElement('div');
    avatarWrap.className = 'message-avatar me-2';
    var avatar = document.createElement('div');
    avatar.className = 'rounded-circle bg-secondary text-white d-flex align-items-center justify-content-center';
    avatar.style.cssText = 'width: 36px; height: 36px;';
    var initial = (msg.sender_username || msg.senderUsername || '?').charAt(0).toUpperCase();
    avatar.textContent = initial;
    avatarWrap.appendChild(avatar);
    item.appendChild(avatarWrap);

    // Content wrapper
    var contentWrap = document.createElement('div');
    contentWrap.className = 'message-content flex-grow-1';

    // Header line: username + time + edited
    var headerDiv = document.createElement('div');
    headerDiv.className = 'd-flex align-items-baseline';
    var nameEl = document.createElement('strong');
    nameEl.className = 'me-2';
    nameEl.textContent = msg.sender_username || msg.senderUsername || 'Unknown';
    headerDiv.appendChild(nameEl);

    var timeEl = document.createElement('small');
    timeEl.className = 'text-muted';
    var ts = msg.created_at || msg.createdAt;
    if (ts) {
      var d = new Date(ts);
      timeEl.textContent = d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
    }
    headerDiv.appendChild(timeEl);

    if (msg.edited) {
      var editedEl = document.createElement('small');
      editedEl.className = 'text-muted ms-1';
      editedEl.textContent = '(edited)';
      headerDiv.appendChild(editedEl);
    }
    contentWrap.appendChild(headerDiv);

    // Reply quote
    var replyId = msg.reply_to_id || msg.replyToId;
    if (replyId) {
      var replyDiv = document.createElement('div');
      replyDiv.className = 'reply-quote border-start border-3 border-primary ps-2 mb-1 small text-muted';
      replyDiv.textContent = 'Replying to a message';
      contentWrap.appendChild(replyDiv);
    }

    // Message text
    var textEl = document.createElement('p');
    textEl.className = 'mb-0';
    textEl.textContent = msg.content;
    contentWrap.appendChild(textEl);

    item.appendChild(contentWrap);
    return item;
  }

  // --- Auto-scroll logic ---

  function isScrolledToBottom(el) {
    return el.scrollHeight - el.scrollTop - el.clientHeight < 50;
  }

  function scrollToBottom(el) {
    el.scrollTop = el.scrollHeight;
  }

  // --- New message handler (called from stomp-client.js) ---

  function onNewMessage(msg) {
    var messageList = document.getElementById('message-list');
    if (!messageList) {
      return;
    }

    // Check if already rendered (dedup)
    if (msg.id && messageList.querySelector('[data-message-id="' + msg.id + '"]')) {
      return;
    }

    var wasAtBottom = isScrolledToBottom(messageList);
    var el = createMessageElement(msg);
    messageList.appendChild(el);

    if (wasAtBottom) {
      scrollToBottom(messageList);
    }
  }

  // --- Notification handler (called from stomp-client.js) ---

  function onNotification(notification) {
    if (notification.type === 'UNREAD_UPDATE' && notification.room_id) {
      updateUnreadBadge(notification.room_id, notification.unread_count);
    }
  }

  function updateUnreadBadge(roomId, count) {
    // Find room links in sidebar and update badge
    var roomLinks = document.querySelectorAll('a[href*="/chat/rooms/' + roomId + '"]');
    roomLinks.forEach(function (link) {
      var li = link.closest('li');
      if (!li) {
        return;
      }
      var badge = li.querySelector('.badge');
      if (count > 0) {
        if (!badge) {
          badge = document.createElement('span');
          badge.className = 'badge bg-danger rounded-pill ms-1';
          li.appendChild(badge);
        }
        badge.textContent = count > 999 ? '999+' : count;
      } else if (badge) {
        badge.remove();
      }
    });
  }

  // --- Infinite scroll: load older messages ---

  function loadOlderMessages() {
    var messageList = document.getElementById('message-list');
    if (!messageList || isLoadingHistory) {
      return;
    }

    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    // Get the oldest watermark currently in DOM
    var firstMsg = messageList.querySelector('.message-item[data-watermark]');
    if (!firstMsg) {
      return;
    }

    var oldestWatermark = parseInt(firstMsg.getAttribute('data-watermark'), 10);
    if (isNaN(oldestWatermark) || oldestWatermark <= 1) {
      return; // no older messages possible
    }

    isLoadingHistory = true;

    // Cursor for "before" this watermark: we need messages with watermark < oldestWatermark
    // The API returns watermark > cursor, so we need to compute a cursor that gives us older messages
    // We'll use cursor=0 and let the server return from the beginning, or use a different approach
    // Actually the API is: GET /api/rooms/{roomId}/messages?cursor=X&size=50 returns watermark > X
    // To get messages BEFORE oldestWatermark, we need a different cursor
    // Let's request with cursor = max(0, oldestWatermark - 51) to get the page before
    var cursor = Math.max(0, oldestWatermark - 51);
    var url = '/api/rooms/' + roomId + '/messages?cursor=' + cursor + '&size=50';

    fetch(url, {
      headers: apiHeaders()
    })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('Failed to load history: ' + response.status);
      }
      return response.json();
    })
    .then(function (page) {
      if (!page.messages || page.messages.length === 0) {
        // No more messages — remove load more button
        var loadMoreBtn = document.getElementById('load-more-btn');
        if (loadMoreBtn) {
          loadMoreBtn.parentElement.remove();
        }
        isLoadingHistory = false;
        return;
      }

      // Filter to only messages we don't already have
      var existingIds = new Set();
      messageList.querySelectorAll('.message-item[data-message-id]').forEach(function (el) {
        existingIds.add(el.getAttribute('data-message-id'));
      });

      var newMessages = page.messages.filter(function (msg) {
        return !existingIds.has(msg.id);
      });

      if (newMessages.length === 0) {
        var loadMoreBtn = document.getElementById('load-more-btn');
        if (loadMoreBtn) {
          loadMoreBtn.parentElement.remove();
        }
        isLoadingHistory = false;
        return;
      }

      // Preserve scroll position
      var scrollHeightBefore = messageList.scrollHeight;

      // Find the insertion point (before the first message item)
      var firstItem = messageList.querySelector('.message-item');

      // Insert messages in order (they come sorted by watermark asc)
      newMessages.forEach(function (msg) {
        var el = createMessageElement(msg);
        if (firstItem) {
          messageList.insertBefore(el, firstItem);
        } else {
          messageList.appendChild(el);
        }
      });

      // Restore scroll position so user doesn't jump
      var scrollHeightAfter = messageList.scrollHeight;
      messageList.scrollTop += (scrollHeightAfter - scrollHeightBefore);

      // Update has-more state
      if (!page.has_more && !page.hasMore) {
        var loadMoreBtn = document.getElementById('load-more-btn');
        if (loadMoreBtn) {
          loadMoreBtn.parentElement.remove();
        }
      }

      isLoadingHistory = false;
    })
    .catch(function (err) {
      console.error('[App] Error loading older messages:', err);
      isLoadingHistory = false;
    });
  }

  // --- Send message ---

  function sendCurrentMessage() {
    var textarea = document.getElementById('message-textarea');
    if (!textarea) {
      return;
    }

    var content = textarea.value.trim();
    if (!content) {
      return;
    }

    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    var replyToInput = document.getElementById('reply-to-id');
    var replyToId = replyToInput ? replyToInput.value || null : null;

    if (window.YAC && window.YAC.stomp) {
      window.YAC.stomp.sendMessage(roomId, content, replyToId);
    }

    textarea.value = '';
    textarea.style.height = 'auto';
    cancelReply();
  }

  // --- Reply handling ---

  window.cancelReply = function () {
    var indicator = document.getElementById('reply-indicator');
    var replyToId = document.getElementById('reply-to-id');
    if (indicator) {
      indicator.classList.add('d-none');
    }
    if (replyToId) {
      replyToId.value = '';
    }
  };

  // --- Clipboard paste handler for image uploads ---

  function handlePaste(event) {
    var items = event.clipboardData && event.clipboardData.items;
    if (!items) {
      return;
    }

    for (var i = 0; i < items.length; i++) {
      if (items[i].type.indexOf('image') !== -1) {
        event.preventDefault();
        var file = items[i].getAsFile();
        if (file) {
          uploadImage(file);
        }
        return;
      }
    }
  }

  function uploadImage(file) {
    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    // First send a placeholder message, then attach the image
    // For now, we send the message via REST and then upload the attachment
    var csrfHeader = getCsrfHeader();
    var csrfValue = getCsrfToken();

    // Send a message first to get a messageId for the attachment
    fetch('/api/rooms/' + roomId + '/messages', {
      method: 'POST',
      headers: apiHeaders(),
      body: JSON.stringify({
        room_id: roomId,
        content: '[Image: ' + (file.name || 'pasted-image.png') + ']'
      })
    })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('Failed to send message for image: ' + response.status);
      }
      return response.json();
    })
    .then(function (msg) {
      // Now upload the attachment
      var formData = new FormData();
      formData.append('file', file, file.name || 'pasted-image.png');
      formData.append('messageId', msg.id);

      var uploadHeaders = {};
      if (csrfHeader) {
        uploadHeaders[csrfHeader] = csrfValue;
      }

      return fetch('/api/rooms/' + roomId + '/attachments', {
        method: 'POST',
        headers: uploadHeaders,
        body: formData
      });
    })
    .then(function (response) {
      if (!response.ok) {
        console.error('[App] Image upload failed:', response.status);
      }
    })
    .catch(function (err) {
      console.error('[App] Error uploading image:', err);
    });
  }

  // --- Admin action functions ---

  window.kickMember = function (btn) {
    var userId = btn.getAttribute('data-user-id');
    var username = btn.getAttribute('data-username') || 'this user';
    var roomId = getRoomId();
    if (!roomId || !userId) {
      return;
    }

    showConfirmModal('Remove ' + username + ' from this room?', function () {
      fetch('/api/rooms/' + roomId + '/members/' + userId, {
        method: 'DELETE',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok || response.status === 204) {
          window.location.reload();
        } else {
          return response.json().then(function (err) {
            showErrorModal(err.message || 'Failed to remove member');
          });
        }
      })
      .catch(function (err) {
        console.error('[App] Kick member error:', err);
      });
    });
  };

  window.banMember = function (btn) {
    var userId = btn.getAttribute('data-user-id');
    var username = btn.getAttribute('data-username') || 'this user';
    var roomId = getRoomId();
    if (!roomId || !userId) {
      return;
    }

    showConfirmModal('Ban ' + username + ' from this room?', function () {
      fetch('/api/rooms/' + roomId + '/bans', {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify({ user_id: userId })
      })
      .then(function (response) {
        if (response.ok || response.status === 201) {
          window.location.reload();
        } else {
          return response.json().then(function (err) {
            showErrorModal(err.message || 'Failed to ban member');
          });
        }
      })
      .catch(function (err) {
        console.error('[App] Ban member error:', err);
      });
    });
  };

  window.promoteToAdmin = function (btn) {
    var userId = btn.getAttribute('data-user-id');
    var roomId = getRoomId();
    if (!roomId || !userId) {
      return;
    }

    fetch('/api/rooms/' + roomId + '/members/' + userId + '/role', {
      method: 'PUT',
      headers: apiHeaders(),
      body: JSON.stringify({ role: 'ADMIN' })
    })
    .then(function (response) {
      if (response.ok) {
        window.location.reload();
      } else {
        return response.json().then(function (err) {
          showErrorModal(err.message || 'Failed to promote member');
        });
      }
    })
    .catch(function (err) {
      console.error('[App] Promote error:', err);
    });
  };

  window.demoteToMember = function (btn) {
    var userId = btn.getAttribute('data-user-id');
    var roomId = getRoomId();
    if (!roomId || !userId) {
      return;
    }

    fetch('/api/rooms/' + roomId + '/members/' + userId + '/role', {
      method: 'PUT',
      headers: apiHeaders(),
      body: JSON.stringify({ role: 'MEMBER' })
    })
    .then(function (response) {
      if (response.ok) {
        window.location.reload();
      } else {
        return response.json().then(function (err) {
          showErrorModal(err.message || 'Failed to demote member');
        });
      }
    })
    .catch(function (err) {
      console.error('[App] Demote error:', err);
    });
  };

  window.deleteRoom = function () {
    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    fetch('/api/rooms/' + roomId, {
      method: 'DELETE',
      headers: apiHeaders()
    })
    .then(function (response) {
      if (response.ok || response.status === 204) {
        window.location.href = '/chat';
      } else {
        return response.json().then(function (err) {
          showErrorModal(err.message || 'Failed to delete room');
        });
      }
    })
    .catch(function (err) {
      console.error('[App] Delete room error:', err);
    });
  };

  window.sendInvitation = function () {
    var roomId = getRoomId();
    var usernameInput = document.getElementById('invite-username');
    var feedback = document.getElementById('invite-feedback');
    if (!roomId || !usernameInput) {
      return;
    }

    var username = usernameInput.value.trim();
    if (!username) {
      if (feedback) {
        feedback.textContent = 'Please enter a username';
        feedback.className = 'small text-danger';
      }
      return;
    }

    // First search for the user by username
    fetch('/api/users/search?username=' + encodeURIComponent(username), {
      headers: apiHeaders()
    })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('User not found');
      }
      return response.json();
    })
    .then(function (user) {
      // Send the invitation
      return fetch('/api/rooms/' + roomId + '/invitations', {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify({ user_id: user.id })
      });
    })
    .then(function (response) {
      if (response.ok || response.status === 201) {
        if (feedback) {
          feedback.textContent = 'Invitation sent!';
          feedback.className = 'small text-success';
        }
        usernameInput.value = '';
      } else {
        return response.json().then(function (err) {
          throw new Error(err.message || 'Failed to send invitation');
        });
      }
    })
    .catch(function (err) {
      if (feedback) {
        feedback.textContent = err.message || 'Error sending invitation';
        feedback.className = 'small text-danger';
      }
    });
  };

  // --- Initialization ---

  function init() {
    // Configure HTMX CSRF
    var csrfToken = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    if (csrfToken && csrfHeader) {
      document.body.addEventListener('htmx:configRequest', function (event) {
        event.detail.headers[csrfHeader.content] = csrfToken.content;
      });
    }

    var messageList = document.getElementById('message-list');
    var textarea = document.getElementById('message-textarea');
    var sendBtn = document.getElementById('send-btn');

    // Scroll to bottom on initial load
    if (messageList) {
      scrollToBottom(messageList);

      // Infinite scroll: detect scroll to top
      messageList.addEventListener('scroll', function () {
        if (messageList.scrollTop < 100 && !isLoadingHistory) {
          loadOlderMessages();
        }
      });
    }

    // Load more button click
    var loadMoreBtn = document.getElementById('load-more-btn');
    if (loadMoreBtn) {
      loadMoreBtn.addEventListener('click', function () {
        loadOlderMessages();
      });
    }

    // Send button click
    if (sendBtn) {
      sendBtn.addEventListener('click', function () {
        sendCurrentMessage();
      });
    }

    // Enter key handler for textarea (Enter sends, Shift+Enter for newline)
    if (textarea) {
      textarea.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
          event.preventDefault();
          sendCurrentMessage();
        }
      });

      // Auto-resize textarea
      textarea.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 120) + 'px';
      });

      // Clipboard paste handler for images
      textarea.addEventListener('paste', handlePaste);
    }

    // File input handlers
    var fileInput = document.getElementById('file-input');
    if (fileInput) {
      fileInput.addEventListener('change', function () {
        if (this.files && this.files[0]) {
          uploadImage(this.files[0]);
          this.value = '';
        }
      });
    }

    var imageInput = document.getElementById('image-input');
    if (imageInput) {
      imageInput.addEventListener('change', function () {
        if (this.files && this.files[0]) {
          uploadImage(this.files[0]);
          this.value = '';
        }
      });
    }

    console.log('[App] YAC app initialized');
  }

  // Expose callbacks for stomp-client.js
  window.YAC = window.YAC || {};
  window.YAC.app = {
    onNewMessage: onNewMessage,
    onNotification: onNotification
  };

  document.addEventListener('DOMContentLoaded', init);
})();
