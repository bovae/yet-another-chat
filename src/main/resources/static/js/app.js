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

    // Attachment rendering
    var attachments = msg.attachments;
    if (attachments && attachments.length > 0) {
      var roomId = getRoomId();
      for (var ai = 0; ai < attachments.length; ai++) {
        var att = attachments[ai];
        var attId = att.id;
        var attContentType = att.content_type || att.contentType || '';
        var attFileName = att.original_file_name || att.originalFileName || 'file';
        var downloadUrl = '/api/rooms/' + roomId + '/attachments/' + attId + '/download';

        if (attContentType.indexOf('image/') === 0) {
          var imgLink = document.createElement('a');
          imgLink.href = downloadUrl;
          imgLink.target = '_blank';
          var imgEl = document.createElement('img');
          imgEl.src = downloadUrl;
          imgEl.alt = attFileName;
          imgEl.style.cssText = 'max-width: 400px; cursor: pointer; display: block; margin-top: 4px; border-radius: 4px;';
          imgLink.appendChild(imgEl);
          contentWrap.appendChild(imgLink);
        } else {
          var fileLink = document.createElement('a');
          fileLink.href = downloadUrl;
          fileLink.className = 'small d-block mt-1';
          fileLink.textContent = '\uD83D\uDCCE ' + attFileName;
          contentWrap.appendChild(fileLink);
        }
      }
    }

    item.appendChild(contentWrap);

    // Message actions (edit + delete) for own messages
    var senderId = msg.sender_id || msg.senderId;
    if (window.YAC_USER && senderId && String(senderId) === String(window.YAC_USER.id)) {
      var actionsDiv = document.createElement('div');
      actionsDiv.className = 'message-actions ms-2 d-flex gap-1';

      var editBtn = document.createElement('button');
      editBtn.className = 'btn btn-sm btn-outline-secondary';
      editBtn.title = 'Edit message';
      editBtn.setAttribute('data-message-id', msg.id);
      editBtn.textContent = '\u270F\uFE0F';
      actionsDiv.appendChild(editBtn);

      var deleteBtn = document.createElement('button');
      deleteBtn.className = 'btn btn-sm btn-outline-secondary';
      deleteBtn.title = 'Delete message';
      deleteBtn.setAttribute('data-message-id', msg.id);
      deleteBtn.textContent = '\uD83D\uDDD1';
      actionsDiv.appendChild(deleteBtn);

      item.appendChild(actionsDiv);
    }

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

  // --- Message edit handler ---

  function handleEditMessage(messageItem, messageId, roomId) {
    var contentWrap = messageItem.querySelector('.message-content');
    if (!contentWrap) {
      return;
    }
    var textEl = contentWrap.querySelector('p.mb-0');
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
        var headerDiv = contentWrap.querySelector('.d-flex.align-items-baseline');
        if (headerDiv && !headerDiv.querySelector('.text-muted.ms-1')) {
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
        var small = headerDiv.querySelector('small');
        if (h6 && updated.name) {
          h6.textContent = updated.name;
        }
        if (small) {
          small.textContent = updated.description || '';
        }
      }

      // Close modal
      var modalEl = document.getElementById('roomSettingsModal');
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

    // --- Event delegation for message edit and delete ---
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
        var roomId = getRoomId();
        if (!roomId) {
          return;
        }

        var title = btn.getAttribute('title') || '';

        if (title === 'Edit message' || btn.textContent.trim() === '\u270F\uFE0F') {
          // --- Edit message ---
          handleEditMessage(messageItem, messageId, roomId);
        } else if (title === 'Delete message' || title === 'Delete' || title === 'Admin delete' || btn.textContent.trim() === '\uD83D\uDDD1') {
          // --- Delete message ---
          handleDeleteMessage(messageItem, messageId, roomId);
        }
      });
    }

    // --- Banned users modal population ---
    var bannedUsersModalEl = document.getElementById('bannedUsersModal');
    if (bannedUsersModalEl) {
      bannedUsersModalEl.addEventListener('show.bs.modal', function () {
        populateBannedUsersModal();
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
