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
    var senderId = msg.sender_id || msg.senderId;
    var isOwn = window.YAC_USER && senderId && String(senderId) === String(window.YAC_USER.id);
    var senderDisplayName = msg.sender_display_name || msg.senderDisplayName;
    var senderUsername = msg.sender_username || msg.senderUsername || 'Unknown';
    var displayName = senderDisplayName || senderUsername;

    // Outer container with alignment class
    var item = document.createElement('div');
    item.className = 'message-item d-flex mb-3 ' + (isOwn ? 'message-own' : 'message-other');
    item.setAttribute('data-message-id', msg.id);
    item.setAttribute('data-watermark', msg.watermark);

    // Avatar (outside bubble)
    var avatar = document.createElement('div');
    avatar.className = 'message-avatar d-flex align-items-center justify-content-center rounded-circle bg-secondary text-white fw-bold';
    avatar.style.cssText = 'width: 36px; height: 36px; font-size: 0.85rem; flex-shrink: 0;';
    avatar.textContent = displayName.charAt(0).toUpperCase();
    item.appendChild(avatar);

    // Bubble wrapper
    var bubble = document.createElement('div');
    bubble.className = 'message-bubble ms-2';

    // Header: username + timestamp + edited
    var headerDiv = document.createElement('div');
    headerDiv.className = 'message-header small text-muted mb-1';
    var nameEl = document.createElement('strong');
    nameEl.textContent = displayName;
    headerDiv.appendChild(nameEl);

    var timeEl = document.createElement('span');
    var ts = msg.created_at || msg.createdAt;
    if (ts) {
      var d = new Date(ts);
      timeEl.textContent = ' ' + d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
    }
    headerDiv.appendChild(timeEl);

    if (msg.edited) {
      var editedEl = document.createElement('small');
      editedEl.className = 'ms-1';
      editedEl.textContent = '(edited)';
      headerDiv.appendChild(editedEl);
    }
    bubble.appendChild(headerDiv);

    // Reply quote (enriched with sender username and content snippet)
    var replyId = msg.reply_to_id || msg.replyToId;
    if (replyId) {
      item.setAttribute('data-reply-to-id', replyId);
      var replyDiv = document.createElement('div');
      replyDiv.className = 'reply-quote small bg-light border-start border-3 border-primary p-2 mb-1 rounded';
      var replySender = msg.reply_to_sender_username || msg.replyToSenderUsername;
      if (replySender) {
        var replyLabel = document.createTextNode('Replying to ');
        replyDiv.appendChild(replyLabel);
        var replyStrong = document.createElement('strong');
        replyStrong.textContent = replySender;
        replyDiv.appendChild(replyStrong);
        var replySnippet = msg.reply_to_content_snippet || msg.replyToContentSnippet || '';
        if (replySnippet) {
          var snippetText = document.createTextNode(': ' + replySnippet);
          replyDiv.appendChild(snippetText);
        }
      } else {
        replyDiv.textContent = 'Original message deleted';
      }
      bubble.appendChild(replyDiv);
    }

    // Message text
    var textEl = document.createElement('div');
    textEl.className = 'message-text';
    textEl.textContent = msg.content;
    bubble.appendChild(textEl);

    // Attachment rendering
    var attachments = msg.attachments;
    if (attachments && attachments.length > 0) {
      var attContainer = document.createElement('div');
      attContainer.className = 'message-attachments mt-1';
      var roomId = getRoomId();
      for (var ai = 0; ai < attachments.length; ai++) {
        var att = attachments[ai];
        var attId = att.id;
        var attContentType = att.content_type || att.contentType || '';
        var attFileName = att.original_file_name || att.originalFileName || 'file';
        var downloadUrl = '/api/rooms/' + roomId + '/attachments/' + attId + '/download';

        if (attContentType.indexOf('image/') === 0) {
          var imgEl = document.createElement('img');
          imgEl.src = downloadUrl;
          imgEl.alt = attFileName;
          imgEl.className = 'img-fluid rounded';
          imgEl.style.cssText = 'max-width: 400px;';
          attContainer.appendChild(imgEl);
        } else {
          var fileLink = document.createElement('a');
          fileLink.href = downloadUrl;
          fileLink.className = 'small';
          fileLink.textContent = attFileName;
          attContainer.appendChild(fileLink);
        }

        // Attachment comment (R1-49)
        if (att.comment) {
          var commentEl = document.createElement('div');
          commentEl.className = 'attachment-comment small text-muted';
          commentEl.textContent = att.comment;
          attContainer.appendChild(commentEl);
        }
      }
      bubble.appendChild(attContainer);
    }

    // Message actions (reply on all, edit + delete on own)
    var actionsDiv = document.createElement('div');
    actionsDiv.className = 'message-actions mt-1';

    // Reply button on all messages
    var replyBtn = document.createElement('button');
    replyBtn.className = 'btn btn-sm btn-outline-secondary reply-btn';
    replyBtn.title = 'Reply';
    replyBtn.setAttribute('data-message-id', msg.id);
    replyBtn.setAttribute('data-sender-username', senderUsername);
    replyBtn.textContent = '\u21A9';
    actionsDiv.appendChild(replyBtn);

    if (isOwn) {
      var editBtn = document.createElement('button');
      editBtn.className = 'btn btn-sm btn-outline-secondary edit-btn';
      editBtn.title = 'Edit';
      editBtn.setAttribute('data-message-id', msg.id);
      editBtn.textContent = '\u270F\uFE0F';
      actionsDiv.appendChild(editBtn);

      var deleteBtn = document.createElement('button');
      deleteBtn.className = 'btn btn-sm btn-outline-danger delete-btn';
      deleteBtn.title = 'Delete';
      deleteBtn.setAttribute('data-message-id', msg.id);
      deleteBtn.textContent = '\uD83D\uDDD1';
      actionsDiv.appendChild(deleteBtn);
    }

    bubble.appendChild(actionsDiv);
    item.appendChild(bubble);

    return item;
  }

  // --- Auto-scroll logic ---

  function isScrolledToBottom(el) {
    return el.scrollHeight - el.scrollTop - el.clientHeight < 50;
  }

  function scrollToBottom(el) {
    el.scrollTop = el.scrollHeight;
  }

  // Insert a message element at its watermark-ordered position so live messages
  // arriving during reconnect catch-up land in the right place (R1-22).
  function insertMessageInOrder(messageList, el, watermark) {
    if (watermark == null || isNaN(watermark)) {
      messageList.appendChild(el);
      return;
    }
    var items = messageList.querySelectorAll('.message-item[data-watermark]');
    for (var i = 0; i < items.length; i++) {
      var wm = parseInt(items[i].getAttribute('data-watermark'), 10);
      if (!isNaN(wm) && wm > watermark) {
        messageList.insertBefore(el, items[i]);
        return;
      }
    }
    messageList.appendChild(el);
  }

  // --- New message handler (called from stomp-client.js) ---

  function onNewMessage(msg) {
    var messageList = document.getElementById('message-list');
    if (!messageList) {
      return;
    }

    // Dedup — but a re-broadcast that now carries attachments (image upload completes
    // after its text message) replaces the rendered copy in place instead of dropping.
    var existing = msg.id ? messageList.querySelector('[data-message-id="' + msg.id + '"]') : null;
    if (existing) {
      if (msg.attachments && msg.attachments.length > 0 && !existing.querySelector('.message-attachments')) {
        existing.replaceWith(createMessageElement(msg));
      }
      return;
    }

    var wasAtBottom = isScrolledToBottom(messageList);
    var el = createMessageElement(msg);
    var watermark = msg.watermark != null ? parseInt(msg.watermark, 10) : NaN;
    insertMessageInOrder(messageList, el, watermark);

    if (wasAtBottom) {
      scrollToBottom(messageList);
    }

    // Viewing this room means the incoming message is read (R1-19).
    markActiveRoomRead();
  }

  // --- Notification handler (called from stomp-client.js) ---

  function onNotification(notification) {
    var type = notification.type;
    // Any social/unread event can change the navbar aggregate badge (R3-10).
    if (window.YAC && window.YAC.navbar && window.YAC.navbar.refresh) {
      window.YAC.navbar.refresh();
    }
    // Friend-request events arrive live: refresh the sidebar panels/contacts (R2-03).
    if (type === 'FRIEND_REQUEST_CREATED' || type === 'FRIEND_REQUEST_ACCEPTED') {
      if (window.YAC && window.YAC.sidebar && window.YAC.sidebar.refresh) {
        window.YAC.sidebar.refresh();
      }
      return;
    }
    if (type === 'UNREAD_UPDATE') {
      var roomId = notification.room_id || notification.roomId;
      if (!roomId) {
        return;
      }
      var count = notification.unread_count != null ? notification.unread_count : notification.unreadCount;
      // The room I'm currently viewing stays at zero (R1-19).
      if (window.YAC_ROOM && String(window.YAC_ROOM.id) === String(roomId)) {
        updateUnreadBadge(roomId, 0);
        return;
      }
      // A brand-new room not yet in the sidebar → refresh the listing (R1-20).
      var known = document.querySelector('a[href*="/chat/rooms/' + roomId + '"]');
      if (!known && window.YAC && window.YAC.sidebar && window.YAC.sidebar.refresh) {
        window.YAC.sidebar.refresh();
        return;
      }
      updateUnreadBadge(roomId, count);
      return;
    }
    if (type === 'MEMBER_JOINED' || type === 'MEMBER_LEFT' || type === 'MEMBER_BANNED') {
      // Same membership handling whether delivered on the room-events topic or the personal queue.
      onRoomEvent(notification);
    }
  }

  // Acknowledge reads for the room in view so its badge never grows (R1-19). Debounced
  // so a burst of incoming messages produces at most one request per second.
  var readAckTimer = null;

  function markActiveRoomRead() {
    var roomId = getRoomId();
    if (!roomId) {
      return;
    }
    if (readAckTimer) {
      return;
    }
    readAckTimer = setTimeout(function () {
      readAckTimer = null;
      fetch('/api/rooms/' + roomId + '/read', { method: 'POST', headers: apiHeaders() })
        .catch(function (err) {
          console.error('[App] Error acknowledging read:', err);
        });
    }, 1000);
  }

  // --- Membership event handler (called from stomp-client.js) ---

  function onRoomEvent(event) {
    var type = event.type;
    var affectedUserId = event.user_id || event.userId;
    var eventRoomId = event.room_id || event.roomId;
    var myId = window.YAC_USER ? window.YAC_USER.id : null;
    var amAffected = myId && affectedUserId && String(affectedUserId) === String(myId);

    if (type === 'MEMBER_BANNED' && amAffected) {
      // Leave the room view only if I'm actually looking at the room I was removed from.
      if (window.YAC_ROOM && eventRoomId && String(window.YAC_ROOM.id) === String(eventRoomId)) {
        window.location.href = '/chat';
      }
      if (window.YAC && window.YAC.sidebar && window.YAC.sidebar.refresh) {
        window.YAC.sidebar.refresh();
      }
      return;
    }

    if (type === 'MEMBER_LEFT' || type === 'MEMBER_BANNED') {
      removeMemberFromList(affectedUserId);
    }
    // ponytail: MEMBER_JOINED not live-rendered (event lacks role); joiner shows on next load.
  }

  function removeMemberFromList(userId) {
    if (!userId) {
      return;
    }
    var dot = document.querySelector('#member-list-items .presence-dot[data-user-id="' + userId + '"]');
    if (!dot) {
      return;
    }
    var li = dot.closest('li');
    if (li) {
      li.remove();
    }
    var countBadge = document.getElementById('member-count');
    if (countBadge) {
      var n = parseInt(countBadge.textContent, 10);
      if (!isNaN(n) && n > 0) {
        countBadge.textContent = n - 1;
      }
    }
  }

  // --- Message edited handler (called from stomp-client.js) ---

  function onMessageEdited(event) {
    var messageId = event.message_id || event.messageId;
    if (!messageId) {
      return;
    }
    var item = document.querySelector('.message-item[data-message-id="' + messageId + '"]');
    if (!item) {
      return;
    }
    var textEl = item.querySelector('.message-text') || item.querySelector('p.mb-0');
    if (textEl) {
      textEl.textContent = event.content;
    }
    var headerDiv = item.querySelector('.message-header') || item.querySelector('.d-flex.align-items-baseline');
    if (headerDiv && !headerDiv.querySelector('small')) {
      var editedEl = document.createElement('small');
      editedEl.className = 'text-muted ms-1';
      editedEl.textContent = '(edited)';
      headerDiv.appendChild(editedEl);
    }
  }

  // Single unread-badge implementation lives in sidebar.js; delegate to it (R1-80).
  function updateUnreadBadge(roomId, count) {
    if (window.YAC && window.YAC.sidebar && window.YAC.sidebar.updateUnreadBadge) {
      window.YAC.sidebar.updateUnreadBadge(roomId, count);
    }
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

    // Backward pagination: server returns the 50 messages older than this watermark,
    // oldest-first, robust to deletion gaps (R1-01, R1-02).
    var url = '/api/rooms/' + roomId + '/messages?before=' + oldestWatermark + '&size=50';

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

    // Keep the composer text if the socket is down — never silently drop a message (R2-02).
    var sent = window.YAC && window.YAC.stomp && window.YAC.stomp.sendMessage(roomId, content, replyToId);
    if (!sent) {
      return;
    }

    textarea.value = '';
    textarea.style.height = 'auto';
    cancelReply();
  }

  // Toggle the "Reconnecting…" banner and Send availability with the socket state (R2-02).
  function updateConnectionState(connected) {
    var banner = document.getElementById('connection-banner');
    if (banner) {
      banner.classList.toggle('d-none', connected);
    }
    var sendBtn = document.getElementById('send-btn');
    if (sendBtn) {
      sendBtn.disabled = !connected;
    }
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

      // Optional attachment comment from the composer (R1-49)
      var commentInput = document.getElementById('attachment-comment');
      if (commentInput) {
        var comment = commentInput.value.trim();
        if (comment) {
          formData.append('comment', comment);
        }
        commentInput.value = '';
      }

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

  // --- Message edit handler ---

  function handleEditMessage(messageItem, messageId, roomId) {
    var contentWrap = messageItem.querySelector('.message-bubble') || messageItem.querySelector('.message-content');
    if (!contentWrap) {
      return;
    }
    var textEl = contentWrap.querySelector('.message-text') || contentWrap.querySelector('p.mb-0');
    if (!textEl) {
      return;
    }

    // Prevent double-editing
    if (contentWrap.querySelector('.edit-textarea')) {
      return;
    }

    var originalText = textEl.textContent;
    textEl.style.display = 'none';

    var editArea = document.createElement('textarea');
    editArea.className = 'form-control form-control-sm edit-textarea mb-1';
    editArea.value = originalText;
    editArea.rows = 2;

    var btnWrap = document.createElement('div');
    btnWrap.className = 'd-flex gap-1 mb-1';

    var saveBtn = document.createElement('button');
    saveBtn.className = 'btn btn-sm btn-primary';
    saveBtn.textContent = 'Save';
    saveBtn.type = 'button';

    var cancelBtn = document.createElement('button');
    cancelBtn.className = 'btn btn-sm btn-secondary';
    cancelBtn.textContent = 'Cancel';
    cancelBtn.type = 'button';

    btnWrap.appendChild(saveBtn);
    btnWrap.appendChild(cancelBtn);

    var errorDiv = document.createElement('div');
    errorDiv.className = 'text-danger small';

    // Insert after the hidden <p>
    textEl.parentNode.insertBefore(editArea, textEl.nextSibling);
    editArea.parentNode.insertBefore(btnWrap, editArea.nextSibling);
    btnWrap.parentNode.insertBefore(errorDiv, btnWrap.nextSibling);

    editArea.focus();

    cancelBtn.addEventListener('click', function () {
      textEl.style.display = '';
      editArea.remove();
      btnWrap.remove();
      errorDiv.remove();
    });

    saveBtn.addEventListener('click', function () {
      var newContent = editArea.value.trim();
      if (!newContent) {
        errorDiv.textContent = 'Message cannot be empty';
        return;
      }

      saveBtn.disabled = true;
      fetch('/api/rooms/' + roomId + '/messages/' + messageId, {
        method: 'PUT',
        headers: apiHeaders(),
        body: JSON.stringify({ content: newContent })
      })
      .then(function (response) {
        if (response.ok) {
          return response.json();
        }
        return response.json().then(function (err) {
          throw new Error(err.message || 'Failed to edit message');
        });
      })
      .then(function (updated) {
        textEl.textContent = updated.content;
        textEl.style.display = '';
        editArea.remove();
        btnWrap.remove();
        errorDiv.remove();

        // Add (edited) indicator if not already present
        var headerDiv = contentWrap.querySelector('.message-header') || contentWrap.querySelector('.d-flex.align-items-baseline');
        if (headerDiv && !headerDiv.querySelector('small')) {
          var editedEl = document.createElement('small');
          editedEl.className = 'text-muted ms-1';
          editedEl.textContent = '(edited)';
          headerDiv.appendChild(editedEl);
        }
      })
      .catch(function (err) {
        errorDiv.textContent = err.message || 'Error editing message';
        saveBtn.disabled = false;
      });
    });
  }

  // --- Message delete handler ---

  function handleDeleteMessage(messageItem, messageId, roomId) {
    showConfirmModal('Delete this message?', function () {
      fetch('/api/rooms/' + roomId + '/messages/' + messageId, {
        method: 'DELETE',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok || response.status === 204) {
          messageItem.remove();

          // Update reply quotes that reference the deleted message
          var replyingItems = document.querySelectorAll('.message-item[data-reply-to-id="' + messageId + '"]');
          replyingItems.forEach(function (item) {
            var replyQuote = item.querySelector('.reply-quote');
            if (replyQuote) {
              replyQuote.innerHTML = '';
              replyQuote.textContent = 'Original message deleted';
            }
          });
        } else {
          return response.json().then(function (err) {
            showErrorModal(err.message || 'Failed to delete message');
          });
        }
      })
      .catch(function (err) {
        showErrorModal('Error deleting message');
      });
    });
  }

  // --- Banned users modal population ---

  function populateBannedUsersModal() {
    var roomId = getRoomId();
    var listEl = document.getElementById('banned-users-list');
    if (!roomId || !listEl) {
      return;
    }

    listEl.innerHTML = '<p class="text-muted small">Loading...</p>';

    fetch('/api/rooms/' + roomId + '/bans', {
      headers: apiHeaders()
    })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('Failed to load banned users');
      }
      return response.json();
    })
    .then(function (bans) {
      listEl.innerHTML = '';

      if (!bans || bans.length === 0) {
        listEl.innerHTML = '<p class="text-muted small">No banned users</p>';
        return;
      }

      bans.forEach(function (ban) {
        var entry = document.createElement('div');
        entry.className = 'd-flex align-items-center justify-content-between py-2 border-bottom';
        entry.setAttribute('data-ban-user-id', ban.user_id || ban.userId);

        var infoDiv = document.createElement('div');
        var nameEl = document.createElement('strong');
        nameEl.className = 'small';
        nameEl.textContent = ban.username;
        infoDiv.appendChild(nameEl);

        var detailEl = document.createElement('div');
        detailEl.className = 'text-muted small';
        var bannedByName = ban.banned_by_username || ban.bannedByUsername || 'Unknown';
        var banDate = ban.created_at || ban.createdAt;
        var dateStr = banDate ? new Date(banDate).toLocaleDateString() : '';
        detailEl.textContent = 'Banned by ' + bannedByName + (dateStr ? ' on ' + dateStr : '');
        infoDiv.appendChild(detailEl);

        var unbanBtn = document.createElement('button');
        unbanBtn.className = 'btn btn-sm btn-outline-warning';
        unbanBtn.textContent = 'Unban';
        unbanBtn.type = 'button';

        unbanBtn.addEventListener('click', function () {
          var userId = ban.user_id || ban.userId;
          unbanBtn.disabled = true;
          fetch('/api/rooms/' + roomId + '/bans/' + userId, {
            method: 'DELETE',
            headers: apiHeaders()
          })
          .then(function (response) {
            if (response.ok || response.status === 204) {
              entry.remove();
              // Check if list is now empty
              if (!listEl.querySelector('[data-ban-user-id]')) {
                listEl.innerHTML = '<p class="text-muted small">No banned users</p>';
              }
            } else {
              return response.json().then(function (err) {
                showErrorModal(err.message || 'Failed to unban user');
                unbanBtn.disabled = false;
              });
            }
          })
          .catch(function (err) {
            showErrorModal('Error unbanning user');
            unbanBtn.disabled = false;
          });
        });

        entry.appendChild(infoDiv);
        entry.appendChild(unbanBtn);
        listEl.appendChild(entry);
      });
    })
    .catch(function (err) {
      listEl.innerHTML = '<p class="text-danger small">Error loading banned users</p>';
    });
  }

  // --- Manage Room modal: Members & Admins tabs (R2-08) ---

  function populateManageRoom() {
    // Banned tab reuses the existing banned-users-list renderer.
    populateBannedUsersModal();

    var roomId = getRoomId();
    var membersEl = document.getElementById('manage-members-list');
    var adminsEl = document.getElementById('manage-admins-list');
    if (!roomId || !membersEl) {
      return;
    }
    membersEl.innerHTML = '<p class="text-muted small">Loading...</p>';
    if (adminsEl) {
      adminsEl.innerHTML = '<p class="text-muted small">Loading...</p>';
    }

    fetch('/api/rooms/' + roomId + '/members', { headers: apiHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.json();
      })
      .then(function (members) {
        renderManageMembers(members, membersEl, adminsEl);
      })
      .catch(function (err) {
        console.error('[App] Error loading members:', err);
        membersEl.innerHTML = '<p class="text-danger small">Failed to load members</p>';
      });
  }

  function renderManageMembers(members, membersEl, adminsEl) {
    var myId = window.YAC_USER ? String(window.YAC_USER.id) : null;
    membersEl.innerHTML = '';
    if (adminsEl) {
      adminsEl.innerHTML = '';
    }
    var adminCount = 0;

    members.forEach(function (m) {
      var userId = m.user_id || m.userId;
      var username = m.username;
      var displayName = m.display_name || m.displayName || username;
      var role = m.role;
      var isSelf = myId && String(userId) === myId;

      var row = buildMemberRow(userId, username, displayName, role, isSelf, false);
      row.setAttribute('data-member-name', (displayName + ' ' + username).toLowerCase());
      membersEl.appendChild(row);

      if ((role === 'OWNER' || role === 'ADMIN') && adminsEl) {
        adminsEl.appendChild(buildMemberRow(userId, username, displayName, role, isSelf, true));
        adminCount++;
      }
    });

    if (!membersEl.children.length) {
      membersEl.innerHTML = '<p class="text-muted small">No members</p>';
    }
    if (adminsEl && adminCount === 0) {
      adminsEl.innerHTML = '<p class="text-muted small">No admins</p>';
    }
  }

  function buildMemberRow(userId, username, displayName, role, isSelf, adminsTab) {
    var row = document.createElement('div');
    row.className = 'd-flex align-items-center justify-content-between py-2 border-bottom';

    var info = document.createElement('div');
    var nameEl = document.createElement('strong');
    nameEl.className = 'small';
    nameEl.textContent = displayName;
    info.appendChild(nameEl);
    if (role === 'OWNER') {
      info.appendChild(roleBadge('Owner', 'bg-primary'));
    } else if (role === 'ADMIN') {
      info.appendChild(roleBadge('Admin', 'bg-info'));
    }
    row.appendChild(info);

    // The room owner and yourself carry no moderation actions.
    if (!isSelf && role !== 'OWNER') {
      var actions = document.createElement('div');
      actions.className = 'd-flex gap-1';
      if (adminsTab) {
        actions.appendChild(actionBtn('Demote', 'btn-outline-secondary', userId, username, 'demoteToMember'));
      } else {
        if (role === 'MEMBER') {
          actions.appendChild(actionBtn('Promote', 'btn-outline-secondary', userId, username, 'promoteToAdmin'));
        } else if (role === 'ADMIN') {
          actions.appendChild(actionBtn('Demote', 'btn-outline-secondary', userId, username, 'demoteToMember'));
        }
        actions.appendChild(actionBtn('Remove', 'btn-outline-warning', userId, username, 'kickMember'));
        actions.appendChild(actionBtn('Ban', 'btn-outline-danger', userId, username, 'banMember'));
      }
      row.appendChild(actions);
    }
    return row;
  }

  function roleBadge(text, cls) {
    var badge = document.createElement('span');
    badge.className = 'badge ms-1 ' + cls;
    badge.textContent = text;
    return badge;
  }

  function actionBtn(label, cls, userId, username, handlerName) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm ' + cls + ' py-0 px-1';
    btn.textContent = label;
    btn.setAttribute('data-user-id', userId);
    btn.setAttribute('data-username', username);
    btn.addEventListener('click', function () {
      if (typeof window[handlerName] === 'function') {
        window[handlerName](btn);
      }
    });
    return btn;
  }

  // --- Room settings submission ---

  window.submitRoomSettings = function () {
    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    var nameInput = document.getElementById('room-settings-name');
    var descInput = document.getElementById('room-settings-description');
    var visSelect = document.getElementById('room-settings-visibility');
    var errorEl = document.getElementById('roomSettingsError');

    var body = {
      name: nameInput ? nameInput.value.trim() : null,
      description: descInput ? descInput.value.trim() : null,
      visibility: visSelect ? visSelect.value : null
    };

    if (errorEl) {
      errorEl.textContent = '';
    }

    fetch('/api/rooms/' + roomId, {
      method: 'PUT',
      headers: apiHeaders(),
      body: JSON.stringify(body)
    })
    .then(function (response) {
      if (response.ok) {
        return response.json();
      }
      return response.json().then(function (err) {
        throw new Error(err.message || 'Failed to update room settings');
      });
    })
    .then(function (updated) {
      // Update room header
      var headerDiv = document.querySelector('.chat-header');
      if (headerDiv) {
        var h6 = headerDiv.querySelector('h6');
        if (h6 && updated.name) {
          h6.textContent = updated.name;
        }
      }

      // Close modal
      var modalEl = document.getElementById('manageRoomModal');
      if (modalEl) {
        var modal = bootstrap.Modal.getInstance(modalEl);
        if (modal) {
          modal.hide();
        }
      }
    })
    .catch(function (err) {
      if (errorEl) {
        errorEl.textContent = err.message || 'Error updating room settings';
      }
    });
  };

  // --- Leave room ---

  window.leaveRoom = function () {
    var roomId = getRoomId();
    if (!roomId) {
      return;
    }

    showConfirmModal('Leave this room?', function () {
      fetch('/api/rooms/' + roomId + '/leave', {
        method: 'POST',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok || response.status === 204) {
          window.location.href = '/chat';
        } else {
          return response.json().then(function (err) {
            showErrorModal(err.message || 'Failed to leave room');
          });
        }
      })
      .catch(function (err) {
        showErrorModal('Error leaving room');
      });
    });
  };

  // --- Add friend ---

  window.addFriend = function (btn) {
    var username = btn.getAttribute('data-username');
    if (!username) {
      return;
    }

    fetch('/api/friends/request', {
      method: 'POST',
      headers: apiHeaders(),
      body: JSON.stringify({ username: username })
    })
    .then(function (response) {
      if (response.ok || response.status === 201) {
        btn.textContent = 'Request sent';
        btn.disabled = true;
      } else {
        return response.json().then(function (err) {
          showErrorModal(err.message || 'Failed to send friend request');
        });
      }
    })
    .catch(function (err) {
      showErrorModal('Error sending friend request');
    });
  };

  // --- Add friend by username (sidebar form, R1-50) ---

  window.sendFriendRequestByUsername = function () {
    var usernameInput = document.getElementById('add-friend-username');
    var messageInput = document.getElementById('add-friend-message');
    var feedback = document.getElementById('add-friend-feedback');
    var username = usernameInput ? usernameInput.value.trim() : '';

    if (!username) {
      if (feedback) {
        feedback.textContent = 'Please enter a username';
        feedback.className = 'small text-danger';
      }
      return false;
    }

    var body = { username: username };
    var requestText = messageInput ? messageInput.value.trim() : '';
    if (requestText) {
      body.request_text = requestText;
    }

    fetch('/api/friends/request', {
      method: 'POST',
      headers: apiHeaders(),
      body: JSON.stringify(body)
    })
    .then(function (response) {
      if (response.ok || response.status === 201) {
        if (feedback) {
          feedback.textContent = 'Friend request sent!';
          feedback.className = 'small text-success';
        }
        if (usernameInput) { usernameInput.value = ''; }
        if (messageInput) { messageInput.value = ''; }
      } else {
        return response.json().then(function (err) {
          if (feedback) {
            feedback.textContent = err.message || 'Failed to send friend request';
            feedback.className = 'small text-danger';
          }
        });
      }
    })
    .catch(function () {
      if (feedback) {
        feedback.textContent = 'Error sending friend request';
        feedback.className = 'small text-danger';
      }
    });

    return false;
  };

  // --- Block user ---

  window.blockUser = function (btn) {
    var userId = btn.getAttribute('data-user-id');
    var username = btn.getAttribute('data-username') || 'this user';
    if (!userId) {
      return;
    }

    showConfirmModal('Block user ' + username + '?', function () {
      fetch('/api/user-bans', {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify({ user_id: userId })
      })
      .then(function (response) {
        if (response.ok || response.status === 201) {
          showErrorModal('User ' + username + ' has been blocked.');
        } else {
          return response.json().then(function (err) {
            showErrorModal(err.message || 'Failed to block user');
          });
        }
      })
      .catch(function (err) {
        showErrorModal('Error blocking user');
      });
    });
  };

  // --- Initialization ---

  function init() {
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

    // --- Event delegation for message reply, edit and delete ---
    if (messageList) {
      messageList.addEventListener('click', function (event) {
        var btn = event.target.closest('.message-actions button');
        if (!btn) {
          return;
        }
        var messageItem = btn.closest('.message-item');
        if (!messageItem) {
          return;
        }
        var messageId = btn.getAttribute('data-message-id') || messageItem.getAttribute('data-message-id');
        if (!messageId) {
          return;
        }

        var title = btn.getAttribute('title') || '';
        var text = btn.textContent.trim();

        // --- Reply ---
        if (title === 'Reply' || btn.classList.contains('reply-btn') || text === '\u21A9') {
          var senderName = btn.getAttribute('data-sender-username') || 'Unknown';
          var replyToInput = document.getElementById('reply-to-id');
          var replyIndicator = document.getElementById('reply-indicator');
          var replyToName = document.getElementById('reply-to-name');
          if (replyToInput) {
            replyToInput.value = messageId;
          }
          if (replyToName) {
            replyToName.textContent = senderName;
          }
          if (replyIndicator) {
            replyIndicator.classList.remove('d-none');
          }
          var textarea = document.getElementById('message-textarea');
          if (textarea) {
            textarea.focus();
          }
          return;
        }

        var roomId = getRoomId();
        if (!roomId) {
          return;
        }

        if (title === 'Edit message' || title === 'Edit' || btn.classList.contains('edit-btn') || text === '\u270F\uFE0F') {
          // --- Edit message ---
          handleEditMessage(messageItem, messageId, roomId);
        } else if (title === 'Delete message' || title === 'Delete' || title === 'Admin delete' || btn.classList.contains('delete-btn') || text === '\uD83D\uDDD1') {
          // --- Delete message ---
          handleDeleteMessage(messageItem, messageId, roomId);
        }
      });
    }

    // Reflect the WS connection state in the composer (R2-02).
    if (window.YAC && window.YAC.stomp && window.YAC.stomp.onConnectionChange) {
      window.YAC.stomp.onConnectionChange(updateConnectionState);
    }

    // --- Manage Room modal population (R2-08) ---
    var manageRoomModalEl = document.getElementById('manageRoomModal');
    if (manageRoomModalEl) {
      manageRoomModalEl.addEventListener('show.bs.modal', populateManageRoom);
    }

    var manageMembersSearch = document.getElementById('manage-members-search');
    if (manageMembersSearch) {
      manageMembersSearch.addEventListener('input', function () {
        var term = manageMembersSearch.value.trim().toLowerCase();
        document.querySelectorAll('#manage-members-list [data-member-name]').forEach(function (row) {
          row.style.display = (!term || row.getAttribute('data-member-name').indexOf(term) !== -1) ? '' : 'none';
        });
      });
    }

    console.log('[App] YAC app initialized');
  }

  // --- Message deleted handler (called from stomp-client.js) ---

  function onMessageDeleted(event) {
    var messageId = event.message_id;
    if (!messageId) {
      return;
    }

    // Remove the message element from the DOM
    var messageEl = document.querySelector('.message-item[data-message-id="' + messageId + '"]');
    if (messageEl) {
      messageEl.remove();
    }

    // Update any reply quotes that reference the deleted message
    var replyQuotes = document.querySelectorAll('.message-item[data-reply-to-id="' + messageId + '"] .reply-quote');
    replyQuotes.forEach(function (quote) {
      quote.innerHTML = '';
      quote.textContent = 'Original message deleted';
    });
  }

  // Expose callbacks for stomp-client.js
  window.YAC = window.YAC || {};
  window.YAC.app = {
    onNewMessage: onNewMessage,
    onNotification: onNotification,
    onMessageDeleted: onMessageDeleted,
    onMessageEdited: onMessageEdited,
    onRoomEvent: onRoomEvent
  };

  document.addEventListener('DOMContentLoaded', init);
})();
