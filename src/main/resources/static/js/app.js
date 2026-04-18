// YAC - HTMX configuration and global event handlers

document.addEventListener('DOMContentLoaded', function () {
  // Configure HTMX to include CSRF token
  var csrfToken = document.querySelector('meta[name="_csrf"]');
  var csrfHeader = document.querySelector('meta[name="_csrf_header"]');

  if (csrfToken && csrfHeader) {
    document.body.addEventListener('htmx:configRequest', function (event) {
      event.detail.headers[csrfHeader.content] = csrfToken.content;
    });
  }

  console.log('YAC app initialized');
});
