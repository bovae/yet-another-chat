// YAC - Profile page form handlers: display name, password change, account deletion

(function () {
  'use strict';

  // --- CSRF helpers (same pattern as app.js) ---

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

  // --- Feedback helpers ---

  function showFeedback(container, message, isError) {
    var existing = container.querySelector('.profile-feedback');
    if (existing) {
      existing.remove();
    }
    var div = document.createElement('div');
    div.className = 'profile-feedback small mt-2 ' + (isError ? 'text-danger' : 'text-success');
    div.textContent = message;
    container.appendChild(div);
  }

  function clearFeedback(container) {
    var existing = container.querySelector('.profile-feedback');
    if (existing) {
      existing.remove();
    }
  }

  // --- Display name form ---

  function initDisplayNameForm() {
    var form = document.getElementById('display-name-form');
    if (!form) {
      return;
    }

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      clearFeedback(form);

      var displayName = document.getElementById('displayName').value.trim();

      fetch('/api/users/me', {
        method: 'PUT',
        headers: apiHeaders(),
        body: JSON.stringify({ displayName: displayName })
      })
      .then(function (response) {
        if (response.ok) {
          return response.json().then(function () {
            showFeedback(form, 'Display name updated successfully.', false);
          });
        }
        return response.json().then(function (err) {
          showFeedback(form, err.message || 'Failed to update display name.', true);
        });
      })
      .catch(function () {
        showFeedback(form, 'An error occurred while updating display name.', true);
      });
    });
  }

  // --- Change password form ---

  function initChangePasswordForm() {
    var form = document.getElementById('change-password-form');
    if (!form) {
      return;
    }

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      clearFeedback(form);

      var currentPassword = document.getElementById('currentPassword').value;
      var newPassword = document.getElementById('newPassword').value;
      var confirmNewPassword = document.getElementById('confirmNewPassword').value;

      if (newPassword !== confirmNewPassword) {
        showFeedback(form, 'New passwords do not match.', true);
        return;
      }

      if (!currentPassword || !newPassword) {
        showFeedback(form, 'Please fill in all password fields.', true);
        return;
      }

      fetch('/api/password/change', {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify({ currentPassword: currentPassword, newPassword: newPassword })
      })
      .then(function (response) {
        if (response.ok) {
          document.getElementById('currentPassword').value = '';
          document.getElementById('newPassword').value = '';
          document.getElementById('confirmNewPassword').value = '';
          showFeedback(form, 'Password changed successfully.', false);
          return;
        }
        return response.json().then(function (err) {
          showFeedback(form, err.message || 'Failed to change password.', true);
        });
      })
      .catch(function () {
        showFeedback(form, 'An error occurred while changing password.', true);
      });
    });
  }

  // --- Delete account ---

  function initDeleteAccount() {
    var btn = document.getElementById('delete-account-btn');
    if (!btn) {
      return;
    }

    btn.addEventListener('click', function () {
      if (!confirm('Are you sure you want to delete your account? This action is irreversible.')) {
        return;
      }

      fetch('/api/users/me', {
        method: 'DELETE',
        headers: apiHeaders()
      })
      .then(function (response) {
        if (response.status === 204 || response.ok) {
          window.location.href = '/login';
          return;
        }
        return response.json().then(function (err) {
          var card = btn.closest('.card-body');
          if (card) {
            showFeedback(card, err.message || 'Failed to delete account.', true);
          }
        });
      })
      .catch(function () {
        var card = btn.closest('.card-body');
        if (card) {
          showFeedback(card, 'An error occurred while deleting account.', true);
        }
      });
    });
  }

  // --- Initialization ---

  function init() {
    initDisplayNameForm();
    initChangePasswordForm();
    initDeleteAccount();
    console.log('[Profile] Profile page initialized');
  }

  // Expose namespace
  window.YAC = window.YAC || {};
  window.YAC.profile = {
    init: init
  };

  document.addEventListener('DOMContentLoaded', init);
})();
