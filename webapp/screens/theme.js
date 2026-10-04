// Karen Theme Engine: Seamless Dark & Light Mode Toggle
(function() {
  // Read saved preference or default to 'oled' pure dark mode
  const saved = localStorage.getItem('karen_theme') || 'oled';
  document.documentElement.setAttribute('data-theme', saved);
})();

// Primary Dark and Light Toggle function
function toggleTheme() {
  const current = document.documentElement.getAttribute('data-theme') || 'oled';
  const target = (current === 'light') ? 'oled' : 'light';
  setAppTheme(target);
}

// Alias for explicit naming
function toggleDarkLight() {
  toggleTheme();
}

// Specific OLED vs Charcoal toggle
function toggleOledTheme() {
  const current = document.documentElement.getAttribute('data-theme') || 'oled';
  if (current === 'light') {
    setAppTheme('oled');
  } else {
    const target = current === 'oled' ? 'charcoal' : 'oled';
    setAppTheme(target);
  }
}

function setAppTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('karen_theme', theme);
  
  // Update toggles and icons on current page
  updateThemeUI(theme);

  // Sync to parent window if inside an iframe
  if (window.parent && window.parent !== window) {
    window.parent.postMessage({ type: 'karen_theme_change', theme: theme }, '*');
  }

  // Sync to any iframes inside this window (e.g. index.html)
  const iframes = document.querySelectorAll('iframe');
  iframes.forEach(f => {
    try {
      f.contentWindow?.postMessage({ type: 'karen_theme_change', theme: theme }, '*');
      f.contentDocument?.documentElement.setAttribute('data-theme', theme);
    } catch(e) {}
  });

  if (typeof showToast === 'function') {
    if (theme === 'light') {
      showToast('Switched to Light Mode ☀️');
    } else if (theme === 'oled') {
      showToast('Switched to Dark Mode (OLED Pure Black) 🌙');
    } else {
      showToast('Switched to Charcoal Dark Mode 🌑');
    }
  }
}

function updateThemeUI(theme) {
  const isDark = (theme !== 'light');

  // Update toggle checkboxes (checked = dark mode)
  document.querySelectorAll('.theme-switch-input, .oled-switch-input').forEach(sw => {
    sw.checked = isDark;
  });

  // Update theme toggle icons (show Sun when in dark mode to switch to light, and Moon when in light mode to switch to dark)
  document.querySelectorAll('.theme-toggle-icon').forEach(icon => {
    if (theme === 'light') {
      icon.innerText = 'dark_mode'; // Moon icon to switch to dark
      icon.title = 'Switch to Dark Mode';
      icon.style.color = '#5d5d62';
    } else {
      icon.innerText = 'light_mode'; // Sun icon to switch to light
      icon.title = 'Switch to Light Mode';
      icon.style.color = '#f59e0b';
    }
  });

  // Update theme text badges if any
  document.querySelectorAll('.theme-mode-label').forEach(lbl => {
    if (theme === 'light') lbl.innerText = 'Light Mode';
    else if (theme === 'oled') lbl.innerText = 'OLED Black (#000000)';
    else lbl.innerText = 'Charcoal Dark';
  });

  // Update segmented control buttons if any
  document.querySelectorAll('[data-theme-btn]').forEach(btn => {
    const val = btn.getAttribute('data-theme-btn');
    btn.classList.toggle('active', val === theme);
  });
}

// Listen for postMessage from parent or sibling frames
window.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'karen_theme_change') {
    document.documentElement.setAttribute('data-theme', e.data.theme);
    updateThemeUI(e.data.theme);
  }
});

// Listen for localStorage changes across tabs
window.addEventListener('storage', (e) => {
  if (e.key === 'karen_theme' && e.newValue) {
    document.documentElement.setAttribute('data-theme', e.newValue);
    updateThemeUI(e.newValue);
  }
});

// Sync on DOM loaded
document.addEventListener('DOMContentLoaded', () => {
  const current = localStorage.getItem('karen_theme') || 'oled';
  updateThemeUI(current);
});
