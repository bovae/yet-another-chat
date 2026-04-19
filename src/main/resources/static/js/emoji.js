// YAC - Emoji picker: popup grid of Unicode emoji characters with textarea insertion

(function () {
  'use strict';

  var EMOJIS = [
    '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂',
    '🙂', '😉', '😊', '😇', '🥰', '😍', '🤩', '😘',
    '😋', '😛', '😜', '🤪', '😝', '🤑', '🤗', '🤭',
    '🤫', '🤔', '🙄', '😏', '😌', '😴', '🤤', '😷',
    '🤒', '🥳', '🥺', '😢', '😭', '😤', '😠', '🤯',
    '😱', '😨', '😰', '😥', '😓', '🫡', '🤥', '😶',
    '👍', '👎', '👏', '🙌', '🤝', '✌️', '🤞', '🫶',
    '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '💔',
    '🔥', '⭐', '🌟', '💯', '✅', '❌', '⚡', '💡',
    '🐶', '🐱', '🐻', '🦊', '🐼', '🐸', '🐵', '🦁',
    '🍕', '🍔', '🍟', '🌮', '🍩', '🍪', '☕', '🍺'
  ];

  var popup = null;
  var isOpen = false;

  function buildPopup() {
    var div = document.createElement('div');
    div.id = 'emoji-popup';
    div.style.cssText = [
      'position: absolute',
      'bottom: 100%',
      'left: 0',
      'margin-bottom: 6px',
      'display: grid',
      'grid-template-columns: repeat(8, 1fr)',
      'gap: 2px',
      'padding: 8px',
      'background: #fff',
      'border: 1px solid #dee2e6',
      'border-radius: 8px',
      'box-shadow: 0 4px 12px rgba(0,0,0,0.15)',
      'max-height: 240px',
      'overflow-y: auto',
      'z-index: 1050',
      'width: 320px'
    ].join('; ') + ';';

    for (var i = 0; i < EMOJIS.length; i++) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-light btn-sm p-1';
      btn.style.cssText = 'font-size: 1.3rem; line-height: 1; border: none; cursor: pointer;';
      btn.textContent = EMOJIS[i];
      btn.setAttribute('data-emoji', EMOJIS[i]);
      btn.addEventListener('click', onEmojiClick);
      div.appendChild(btn);
    }

    return div;
  }

  function onEmojiClick(event) {
    event.stopPropagation();
    var emoji = event.currentTarget.getAttribute('data-emoji');
    var textarea = document.getElementById('message-textarea');
    if (!textarea || !emoji) {
      closePopup();
      return;
    }

    var start = textarea.selectionStart;
    var end = textarea.selectionEnd;
    var value = textarea.value;
    textarea.value = value.substring(0, start) + emoji + value.substring(end);

    var newPos = start + emoji.length;
    textarea.selectionStart = newPos;
    textarea.selectionEnd = newPos;
    textarea.focus();

    closePopup();
  }

  function openPopup() {
    var emojiBtn = document.getElementById('emoji-btn');
    if (!emojiBtn) {
      return;
    }

    if (!popup) {
      popup = buildPopup();
    }

    var parent = emojiBtn.parentElement;
    if (parent) {
      parent.style.position = 'relative';
      parent.appendChild(popup);
    }

    isOpen = true;
  }

  function closePopup() {
    if (popup && popup.parentElement) {
      popup.parentElement.removeChild(popup);
    }
    isOpen = false;
  }

  function toggle() {
    if (isOpen) {
      closePopup();
    } else {
      openPopup();
    }
  }

  function init() {
    var emojiBtn = document.getElementById('emoji-btn');
    if (!emojiBtn) {
      return;
    }

    emojiBtn.addEventListener('click', function (event) {
      event.stopPropagation();
      toggle();
    });

    document.addEventListener('click', function (event) {
      if (!isOpen) {
        return;
      }
      if (popup && popup.contains(event.target)) {
        return;
      }
      closePopup();
    });

    console.log('[Emoji] Emoji picker initialized');
  }

  window.YAC = window.YAC || {};
  window.YAC.emoji = {
    toggle: toggle
  };

  document.addEventListener('DOMContentLoaded', init);
})();
