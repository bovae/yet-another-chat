// YAC - Sidebar population, search, friend requests, DM initiation

(function () {
  'use strict';

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

  function getCurrentUserId() {
    return window.YAC_USER ? window.YAC_USER.id : null;
  }

  // On fetch failure, replace a list's content with an error + Retry row rather than
  // leaving the misleading "No rooms/contacts yet" empty state (R3-07).
  function renderErrorRow(listEl, label, retryFn) {
    if (!listEl) {
      return;
    }
    listEl.innerHTML = '';
    var li = document.createElement('li');
    li.className = 'px-2 py-1 small text-danger d-flex align-items-center justify-content-between';
    var span = document.createElement('span');
    span.textContent = label;
    li.appendChild(span);
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm btn-outline-secondary py-0 px-1';
    btn.textContent = 'Retry';
    btn.addEventListener('click', retryFn);
    li.appendChild(btn);
    listEl.appendChild(li);
  }

  // --- 11.1: Sidebar room population ---

  function populateRooms() {
    return fetch('/api/rooms/my', { headers: apiHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Failed to fetch rooms: ' + response.status);
        }
        return response.json();
      })
      .then(function (rooms) {
        var publicList = document.getElementById('public-room-list');
        var privateList = document.getElementById('private-room-list');
        var directList = document.getElementById('direct-chat-list');

        if (publicList) {
          publicList.innerHTML = '';
        }
        if (privateList) {
          privateList.innerHTML = '';
        }
        if (directList) {
          directList.innerHTML = '';
        }

        var publicCount = 0;
        var privateCount = 0;
        var directCount = 0;

        rooms.forEach(function (room) {
          if (room.visibility === 'DIRECT' && !room.other_username && !room.other_display_name) {
            return;
          }
          var li = createRoomItem(room);
          switch (room.visibility) {
            case 'PUBLIC':
              if (publicList) {
                publicList.appendChild(li);
              }
              publicCount++;
              break;
            case 'PRIVATE':
              if (privateList) {
                privateList.appendChild(li);
              }
              privateCount++;
              break;
            case 'DIRECT':
              if (directList) {
                directList.appendChild(li);
              }
              directCount++;
              break;
          }
        });

        if (publicCount === 0 && publicList) {
          publicList.innerHTML = '<li class="px-2 py-1 text-muted small">No rooms yet</li>';
        }
        if (privateCount === 0 && privateList) {
          privateList.innerHTML = '<li class="px-2 py-1 text-muted small">No rooms yet</li>';
        }
        if (directCount === 0 && directList) {
          directList.innerHTML = '<li class="px-2 py-1 text-muted small">No conversations yet</li>';
        }
      })
      .catch(function (err) {
        console.error('[Sidebar] Error loading rooms:', err);
        ['public-room-list', 'private-room-list', 'direct-chat-list'].forEach(function (id) {
          renderErrorRow(document.getElementById(id), 'Failed to load rooms', populateRooms);
        });
      });
  }

  function createRoomItem(room) {
    var li = document.createElement('li');
    li.className = 'px-2 py-1 d-flex align-items-center';

    var a = document.createElement('a');
    a.href = '/chat/rooms/' + room.id;
    a.className = 'text-decoration-none text-dark flex-grow-1 text-truncate';

    // For DIRECT rooms, prefer display name > username > room name
    // For self-DM (both null), show "Saved Messages 🔖"
    if (room.visibility === 'DIRECT') {
      if (room.other_display_name || room.other_username) {
        a.textContent = room.other_display_name || room.other_username || room.name;
      } else {
        a.textContent = 'Saved Messages \uD83D\uDD16';
      }
    } else {
      a.textContent = room.name;
    }

    li.appendChild(a);

    if (room.unread_count > 0) {
      var badge = document.createElement('span');
      badge.className = 'badge bg-danger rounded-pill ms-1';
      badge.textContent = room.unread_count > 999 ? '999+' : room.unread_count;
      li.appendChild(badge);
    }

    return li;
  }

  // --- 11.2: Sidebar contact population ---

  function populateContacts() {
    return fetch('/api/friends', { headers: apiHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Failed to fetch friends: ' + response.status);
        }
        return response.json();
      })
      .then(function (friends) {
        var contactList = document.getElementById('contact-list');
        if (!contactList) {
          return;
        }
        contactList.innerHTML = '';

        if (friends.length === 0) {
          contactList.innerHTML = '<li class="px-2 py-1 text-muted small">No contacts yet</li>';
          return;
        }

        var currentUserId = getCurrentUserId();
        if (!currentUserId) {
          console.warn('[Sidebar] currentUserId is falsy — self-filter will be skipped');
        }
        var userIds = [];

        friends.forEach(function (friendship) {
          var friendId;
          var friendUsername;
          if (currentUserId && String(friendship.requester_id) === String(currentUserId)) {
            friendId = friendship.recipient_id;
            friendUsername = friendship.recipient_username;
          } else {
            friendId = friendship.requester_id;
            friendUsername = friendship.requester_username;
          }

          if (currentUserId && String(friendId) === String(currentUserId)) { return; }

          userIds.push(friendId);

          var li = document.createElement('li');
          li.className = 'px-2 py-1 d-flex align-items-center';

          var dot = document.createElement('span');
          dot.className = 'presence-dot me-2 bg-secondary';
          dot.setAttribute('data-user-id', friendId);
          // Text affordance so presence isn't colour-only (R2-04); refreshed by presence.js.
          dot.setAttribute('role', 'img');
          dot.setAttribute('title', 'Offline');
          dot.setAttribute('aria-label', 'Offline');
          dot.style.cssText = 'width: 8px; height: 8px; border-radius: 50%; display: inline-block;';
          li.appendChild(dot);

          // 11.5: DM initiation from contacts
          var nameSpan = document.createElement('span');
          nameSpan.className = 'text-truncate flex-grow-1';
          nameSpan.style.cursor = 'pointer';
          nameSpan.textContent = friendUsername;
          nameSpan.setAttribute('data-user-id', friendId);
          nameSpan.addEventListener('click', function () {
            initiateDirectChat(friendId);
          });
          li.appendChild(nameSpan);

          // Remove contact (R1-51)
          var removeBtn = document.createElement('button');
          removeBtn.type = 'button';
          removeBtn.className = 'btn btn-sm btn-link text-danger p-0 ms-2';
          removeBtn.title = 'Remove contact';
          removeBtn.textContent = '×';
          removeBtn.setAttribute('data-friendship-id', friendship.id);
          removeBtn.addEventListener('click', function (event) {
            event.stopPropagation();
            removeFriend(friendship.id);
          });
          li.appendChild(removeBtn);

          contactList.appendChild(li);
        });

        // Fetch initial presence status for contacts
        if (window.YAC && window.YAC.presence && window.YAC.presence.fetchInitialPresence) {
          window.YAC.presence.fetchInitialPresence(userIds);
        }

        // Subscribe to presence updates for contacts
        if (window.YAC && window.YAC.presence && window.YAC.presence.subscribeToAllVisibleUsers) {
          setTimeout(function () {
            window.YAC.presence.subscribeToAllVisibleUsers();
          }, 500);
        }
      })
      .catch(function (err) {
        console.error('[Sidebar] Error loading contacts:', err);
        renderErrorRow(document.getElementById('contact-list'), 'Failed to load contacts', populateContacts);
      });
  }

  // --- 11.3: Sidebar search filtering + catalog discovery (R3-03) ---

  var discoverTimer = null;

  function setupSearch() {
    var sidebar = document.querySelector('.chat-sidebar');
    if (!sidebar) {
      return;
    }
    var searchInput = sidebar.querySelector('input[type="text"]');
    if (!searchInput) {
      return;
    }

    searchInput.addEventListener('input', function () {
      var raw = searchInput.value.trim();
      var term = raw.toLowerCase();
      var lists = [
        document.getElementById('public-room-list'),
        document.getElementById('private-room-list'),
        document.getElementById('direct-chat-list'),
        document.getElementById('contact-list')
      ];

      lists.forEach(function (list) {
        if (!list) {
          return;
        }
        var items = list.querySelectorAll('li');
        items.forEach(function (li) {
          if (!term) {
            li.style.removeProperty('display');
            return;
          }
          var text = li.textContent.toLowerCase();
          if (text.indexOf(term) !== -1) {
            li.style.removeProperty('display');
          } else {
            // Room rows carry Bootstrap's `d-flex` (display: flex !important); an inline
            // `display: none` alone loses to it, so set it !important to actually hide (R5-05).
            li.style.setProperty('display', 'none', 'important');
          }
        });
      });

      // Also discover un-joined public rooms from the catalog, debounced (R3-03).
      if (discoverTimer) {
        clearTimeout(discoverTimer);
      }
      discoverTimer = setTimeout(function () {
        renderDiscover(raw);
      }, 300);
    });
  }

  function renderDiscover(term) {
    var existing = document.getElementById('discover-section');
    if (!term) {
      if (existing) {
        existing.remove();
      }
      return;
    }
    fetch('/api/rooms?search=' + encodeURIComponent(term) + '&size=10', { headers: apiHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.json();
      })
      .then(function (page) {
        var rooms = page.content || [];
        // Exclude rooms already in the sidebar (already joined).
        var joined = {};
        document.querySelectorAll('#public-room-list a[href], #private-room-list a[href], #direct-chat-list a[href]')
          .forEach(function (a) {
            var match = a.getAttribute('href').match(/\/chat\/rooms\/([^/?#]+)/);
            if (match) {
              joined[match[1]] = true;
            }
          });
        buildDiscoverSection(rooms.filter(function (room) { return !joined[room.id]; }));
      })
      .catch(function (err) {
        console.warn('[Sidebar] Discover search failed:', err);
      });
  }

  function buildDiscoverSection(rooms) {
    var existing = document.getElementById('discover-section');
    if (existing) {
      existing.remove();
    }
    if (!rooms || rooms.length === 0) {
      return;
    }
    var searchBox = document.querySelector('.chat-sidebar input[type="text"]');
    var anchor = searchBox ? searchBox.closest('.mb-3') : null;
    if (!anchor) {
      return;
    }

    var section = document.createElement('div');
    section.id = 'discover-section';
    section.className = 'mb-3';

    var header = document.createElement('h6');
    header.className = 'text-uppercase text-muted small';
    header.textContent = 'Discover';
    section.appendChild(header);

    var list = document.createElement('ul');
    list.className = 'list-unstyled mb-0';
    rooms.forEach(function (room) {
      var li = document.createElement('li');
      li.className = 'px-2 py-1 small d-flex align-items-center justify-content-between';
      var name = document.createElement('span');
      name.className = 'text-truncate';
      name.textContent = room.name;
      li.appendChild(name);
      var joinBtn = document.createElement('button');
      joinBtn.type = 'button';
      joinBtn.className = 'btn btn-outline-primary btn-sm py-0 px-1';
      joinBtn.textContent = 'Join';
      joinBtn.addEventListener('click', function () {
        joinBtn.disabled = true;
        joinDiscoveredRoom(room.id, joinBtn);
      });
      li.appendChild(joinBtn);
      list.appendChild(li);
    });
    section.appendChild(list);
    anchor.parentNode.insertBefore(section, anchor.nextSibling);
  }

  function joinDiscoveredRoom(roomId, btn) {
    fetch('/api/rooms/' + roomId + '/join', { method: 'POST', headers: apiHeaders() })
      .then(function (response) {
        if (response.ok) {
          window.location.href = '/chat/rooms/' + roomId;
        } else {
          return response.json().then(function (err) {
            if (btn) {
              btn.disabled = false;
            }
            if (window.showErrorModal) {
              window.showErrorModal(err.message || 'Failed to join room');
            }
          });
        }
      })
      .catch(function (err) {
        if (btn) {
          btn.disabled = false;
        }
        console.error('[Sidebar] Join room error:', err);
      });
  }

  // --- 11.4: Friend request panel ---

  function populateFriendRequests() {
    var contactList = document.getElementById('contact-list');
    if (!contactList) {
      return Promise.resolve();
    }

    // Remove existing friend request section if present
    var existing = document.getElementById('friend-requests-section');
    if (existing) {
      existing.remove();
    }

    var section = document.createElement('div');
    section.id = 'friend-requests-section';
    section.className = 'mt-2';

    // Collapsible header
    var header = document.createElement('h6');
    header.className = 'text-uppercase text-muted small d-flex align-items-center';
    header.style.cursor = 'pointer';
    header.setAttribute('data-bs-toggle', 'collapse');
    header.setAttribute('data-bs-target', '#friendRequestsCollapse');
    header.innerHTML = '<span class="me-1">&#9654;</span> Friend Requests '
      + '<span class="badge bg-primary ms-1 d-none" id="friend-requests-badge">0</span>';
    section.appendChild(header);

    var collapseDiv = document.createElement('div');
    collapseDiv.id = 'friendRequestsCollapse';
    collapseDiv.className = 'collapse';
    section.appendChild(collapseDiv);

    // Insert after contact list
    contactList.parentNode.insertBefore(section, contactList.nextSibling);

    var incomingPromise = fetch('/api/friends/requests/incoming', { headers: apiHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .catch(function () { return []; });

    var outgoingPromise = fetch('/api/friends/requests/outgoing', { headers: apiHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .catch(function () { return []; });

    return Promise.all([incomingPromise, outgoingPromise]).then(function (results) {
      var incoming = results[0];
      var outgoing = results[1];

      // Pending-incoming count badge on the header (R2-03), mirroring Room Invitations.
      var badge = document.getElementById('friend-requests-badge');
      if (badge && incoming.length > 0) {
        badge.textContent = incoming.length;
        badge.classList.remove('d-none');
        collapseDiv.classList.add('show');
      }

      if (incoming.length === 0 && outgoing.length === 0) {
        var empty = document.createElement('p');
        empty.className = 'small text-muted px-2';
        empty.textContent = 'No pending requests';
        collapseDiv.appendChild(empty);
        return;
      }

      var list = document.createElement('ul');
      list.className = 'list-unstyled mb-0';

      // Incoming requests
      incoming.forEach(function (req) {
        var li = document.createElement('li');
        li.className = 'px-2 py-1 small d-flex align-items-center justify-content-between';
        li.setAttribute('data-request-id', req.id);

        var info = document.createElement('div');
        var nameEl = document.createElement('strong');
        nameEl.textContent = req.requester_username;
        info.appendChild(nameEl);
        if (req.request_text) {
          var textEl = document.createElement('div');
          textEl.className = 'text-muted';
          textEl.textContent = req.request_text;
          info.appendChild(textEl);
        }
        li.appendChild(info);

        var btnGroup = document.createElement('div');
        btnGroup.className = 'd-flex gap-1';

        var acceptBtn = document.createElement('button');
        acceptBtn.className = 'btn btn-success btn-sm py-0 px-1';
        acceptBtn.textContent = 'Accept';
        acceptBtn.addEventListener('click', function () {
          handleFriendAction(req.id, 'accept', li);
        });
        btnGroup.appendChild(acceptBtn);

        var declineBtn = document.createElement('button');
        declineBtn.className = 'btn btn-outline-danger btn-sm py-0 px-1';
        declineBtn.textContent = 'Decline';
        declineBtn.addEventListener('click', function () {
          handleFriendAction(req.id, 'decline', li);
        });
        btnGroup.appendChild(declineBtn);

        li.appendChild(btnGroup);
        list.appendChild(li);
      });

      // Outgoing requests
      outgoing.forEach(function (req) {
        var li = document.createElement('li');
        li.className = 'px-2 py-1 small d-flex align-items-center justify-content-between';
        li.setAttribute('data-request-id', req.id);

        var nameEl = document.createElement('span');
        nameEl.textContent = req.recipient_username;
        li.appendChild(nameEl);

        var badge = document.createElement('span');
        badge.className = 'badge bg-secondary';
        badge.textContent = 'Pending';
        li.appendChild(badge);

        list.appendChild(li);
      });

      collapseDiv.appendChild(list);
    });
  }

  // --- Room Invitations panel ---

  function populateRoomInvitations() {
    return fetch('/api/rooms/invitations/pending', { headers: apiHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .catch(function () { return []; })
      .then(function (invitations) {
        // Remove existing invitation section if present
        var existing = document.getElementById('room-invitations-section');
        if (existing) {
          existing.remove();
        }

        if (invitations.length === 0) {
          return;
        }

        var friendRequestsSection = document.getElementById('friend-requests-section');
        var contactList = document.getElementById('contact-list');
        var insertAfter = friendRequestsSection || contactList;
        if (!insertAfter) {
          return;
        }

        var section = document.createElement('div');
        section.id = 'room-invitations-section';
        section.className = 'mt-2';

        var header = document.createElement('h6');
        header.className = 'text-uppercase text-muted small d-flex align-items-center';
        header.style.cursor = 'pointer';
        header.setAttribute('data-bs-toggle', 'collapse');
        header.setAttribute('data-bs-target', '#roomInvitationsCollapse');
        header.innerHTML = '<span class="me-1">&#9654;</span> Room Invitations <span class="badge bg-primary ms-1">' + invitations.length + '</span>';
        section.appendChild(header);

        var collapseDiv = document.createElement('div');
        collapseDiv.id = 'roomInvitationsCollapse';
        collapseDiv.className = 'collapse show';
        section.appendChild(collapseDiv);

        var list = document.createElement('ul');
        list.className = 'list-unstyled mb-0';

        invitations.forEach(function (inv) {
          var li = document.createElement('li');
          li.className = 'px-2 py-1 small';
          li.setAttribute('data-invitation-id', inv.invitation_id);

          var info = document.createElement('div');
          info.className = 'd-flex align-items-center justify-content-between';

          var details = document.createElement('div');
          var roomName = document.createElement('strong');
          roomName.textContent = inv.room_name;
          details.appendChild(roomName);
          var inviterText = document.createElement('div');
          inviterText.className = 'text-muted';
          inviterText.textContent = 'from ' + inv.inviter_username;
          details.appendChild(inviterText);
          info.appendChild(details);

          var btnGroup = document.createElement('div');
          btnGroup.className = 'd-flex gap-1';

          var acceptBtn = document.createElement('button');
          acceptBtn.className = 'btn btn-success btn-sm py-0 px-1';
          acceptBtn.textContent = 'Accept';
          acceptBtn.addEventListener('click', function () {
            handleInvitationAction(inv.room_id, inv.invitation_id, 'accept', li);
          });
          btnGroup.appendChild(acceptBtn);

          var declineBtn = document.createElement('button');
          declineBtn.className = 'btn btn-outline-danger btn-sm py-0 px-1';
          declineBtn.textContent = 'Decline';
          declineBtn.addEventListener('click', function () {
            handleInvitationAction(inv.room_id, inv.invitation_id, 'decline', li);
          });
          btnGroup.appendChild(declineBtn);

          info.appendChild(btnGroup);
          li.appendChild(info);
          list.appendChild(li);
        });

        collapseDiv.appendChild(list);
        insertAfter.parentNode.insertBefore(section, insertAfter.nextSibling);
      });
  }

  function handleInvitationAction(roomId, invitationId, action, liElement) {
    if (action === 'accept') {
      fetch('/api/rooms/' + roomId + '/invitations/' + invitationId + '/accept', {
        method: 'POST',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok) {
          window.location.href = '/chat/rooms/' + roomId;
        } else {
          return response.json().then(function (err) {
            if (window.showErrorModal) {
              window.showErrorModal(err.message || 'Failed to accept invitation');
            }
          });
        }
      })
      .catch(function (err) {
        console.error('[Sidebar] Invitation accept error:', err);
      });
    } else {
      fetch('/api/rooms/' + roomId + '/invitations/' + invitationId, {
        method: 'DELETE',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok || response.status === 204) {
          liElement.remove();
          // Remove section if no more invitations
          var section = document.getElementById('room-invitations-section');
          if (section) {
            var remaining = section.querySelectorAll('li[data-invitation-id]');
            if (remaining.length === 0) {
              section.remove();
            }
          }
        } else {
          return response.json().then(function (err) {
            if (window.showErrorModal) {
              window.showErrorModal(err.message || 'Failed to decline invitation');
            }
          });
        }
      })
      .catch(function (err) {
        console.error('[Sidebar] Invitation decline error:', err);
      });
    }
  }

  function handleFriendAction(friendshipId, action, liElement) {
    fetch('/api/friends/' + friendshipId + '/' + action, {
      method: 'POST',
      headers: apiHeaders()
    })
    .then(function (response) {
      if (response.ok) {
        if (action === 'decline') {
          liElement.remove();
        } else {
          // Accept: refresh the whole sidebar to show new contact
          refreshSidebar();
        }
      } else {
        return response.json().then(function (err) {
          if (window.showErrorModal) {
            window.showErrorModal(err.message || 'Failed to ' + action + ' request');
          }
        });
      }
    })
    .catch(function (err) {
      console.error('[Sidebar] Friend action error:', err);
    });
  }

  // --- Saved Messages button handler ---

  function setupSavedMessagesButton() {
    var btn = document.getElementById('saved-messages-btn');
    if (!btn) {
      return;
    }
    btn.addEventListener('click', function () {
      fetch('/api/direct-chats/saved', {
        method: 'POST',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (!response.ok) {
          return response.json().then(function (err) {
            if (window.showErrorModal) {
              window.showErrorModal(err.message || 'Failed to open Saved Messages');
            }
            throw new Error(err.message);
          });
        }
        return response.json();
      })
      .then(function (room) {
        window.location.href = '/chat/rooms/' + room.id;
      })
      .catch(function (err) {
        console.error('[Sidebar] Saved Messages error:', err);
      });
    });
  }

  // --- 11.5: DM initiation from contacts ---

  function initiateDirectChat(userId) {
    fetch('/api/direct-chats', {
      method: 'POST',
      headers: apiHeaders(),
      body: JSON.stringify({ user_id: userId })
    })
    .then(function (response) {
      if (!response.ok) {
        return response.json().then(function (err) {
          if (window.showErrorModal) {
            window.showErrorModal(err.message || 'Failed to open direct chat');
          }
          throw new Error(err.message);
        });
      }
      return response.json();
    })
    .then(function (room) {
      window.location.href = '/chat/rooms/' + room.id;
    })
    .catch(function (err) {
      console.error('[Sidebar] DM initiation error:', err);
    });
  }

  // --- 11.6: URL section param handling ---

  function handleSectionParam() {
    var params = new URLSearchParams(window.location.search);
    var section = params.get('section');
    if (!section) {
      return;
    }

    if (section === 'private') {
      var privateCollapse = document.getElementById('privateRooms');
      if (privateCollapse) {
        var bsCollapse = new bootstrap.Collapse(privateCollapse, { toggle: false });
        bsCollapse.show();
      }
      // Collapse public rooms
      var publicCollapse = document.getElementById('publicRooms');
      if (publicCollapse) {
        var bsPublic = new bootstrap.Collapse(publicCollapse, { toggle: false });
        bsPublic.hide();
      }
    }

    if (section === 'contacts') {
      var contactList = document.getElementById('contact-list');
      if (contactList) {
        setTimeout(function () {
          contactList.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }, 300);
      }
    }
  }

  // --- 11.7: WebSocket unread count update handler ---

  function updateUnreadBadge(roomId, count) {
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

  // --- Contact removal (R1-51) ---

  function removeFriend(friendshipId) {
    if (!friendshipId) {
      return;
    }
    // In-app confirm modal instead of the native confirm() dialog (R5-09).
    window.showConfirmModal('Remove this contact?', function () {
      fetch('/api/friends/' + friendshipId, {
        method: 'DELETE',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.ok || response.status === 204) {
          refreshSidebar();
        } else {
          return response.json().then(function (err) {
            if (window.showErrorModal) {
              window.showErrorModal(err.message || 'Failed to remove contact');
            }
          });
        }
      })
      .catch(function (err) {
        console.error('[Sidebar] Remove friend error:', err);
      });
    });
  }

  // --- Blocked users management (R1-51) ---

  function populateBlockedUsers() {
    var listEl = document.getElementById('blocked-users-list');
    if (!listEl) {
      return;
    }
    listEl.innerHTML = '<p class="text-muted small">Loading...</p>';

    fetch('/api/user-bans', { headers: apiHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Failed to load blocked users');
        }
        return response.json();
      })
      .then(function (bans) {
        listEl.innerHTML = '';
        if (!bans || bans.length === 0) {
          listEl.innerHTML = '<p class="text-muted small">No blocked users</p>';
          return;
        }

        bans.forEach(function (ban) {
          var entry = document.createElement('div');
          entry.className = 'd-flex align-items-center justify-content-between py-2 border-bottom';

          var info = document.createElement('div');
          var nameEl = document.createElement('strong');
          nameEl.className = 'small';
          nameEl.textContent = ban.blocked_display_name || ban.blockedDisplayName
            || ban.blocked_username || ban.blockedUsername || 'User';
          info.appendChild(nameEl);

          var unameEl = document.createElement('div');
          unameEl.className = 'text-muted small';
          unameEl.textContent = '@' + (ban.blocked_username || ban.blockedUsername || '');
          info.appendChild(unameEl);

          var unbanBtn = document.createElement('button');
          unbanBtn.type = 'button';
          unbanBtn.className = 'btn btn-sm btn-outline-warning';
          unbanBtn.textContent = 'Unblock';
          unbanBtn.addEventListener('click', function () {
            unbanBtn.disabled = true;
            fetch('/api/user-bans/' + ban.id, {
              method: 'DELETE',
              headers: apiHeaders()
            })
            .then(function (resp) {
              if (resp.ok || resp.status === 204) {
                entry.remove();
                if (!listEl.querySelector('div')) {
                  listEl.innerHTML = '<p class="text-muted small">No blocked users</p>';
                }
              } else {
                unbanBtn.disabled = false;
                if (window.showErrorModal) {
                  window.showErrorModal('Failed to unblock user');
                }
              }
            })
            .catch(function () {
              unbanBtn.disabled = false;
              if (window.showErrorModal) {
                window.showErrorModal('Error unblocking user');
              }
            });
          });

          entry.appendChild(info);
          entry.appendChild(unbanBtn);
          listEl.appendChild(entry);
        });
      })
      .catch(function () {
        listEl.innerHTML = '<p class="text-danger small">Error loading blocked users</p>';
      });
  }

  // --- Refresh and init ---

  function refreshSidebar() {
    return Promise.all([
      populateRooms(),
      populateContacts()
    ]).then(function () {
      return populateFriendRequests();
    }).then(function () {
      return populateRoomInvitations();
    });
  }

  function init() {
    setupSavedMessagesButton();

    var blockedModalEl = document.getElementById('blockedUsersModal');
    if (blockedModalEl) {
      blockedModalEl.addEventListener('show.bs.modal', populateBlockedUsers);
    }

    refreshSidebar().then(function () {
      setupSearch();
      handleSectionParam();
    });
  }

  // --- Expose public API ---

  window.YAC = window.YAC || {};
  window.YAC.sidebar = {
    refresh: refreshSidebar,
    updateUnreadBadge: updateUnreadBadge
  };

  document.addEventListener('DOMContentLoaded', init);
})();
