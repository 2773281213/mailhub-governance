(() => {
  'use strict';

  const STYLE = `
    .mh-auth-backdrop{position:fixed;inset:0;z-index:10000;background:rgba(3,8,20,.72);display:grid;place-items:center;padding:20px;backdrop-filter:blur(8px)}
    .mh-auth-card{width:min(560px,100%);max-height:92vh;overflow:auto;background:#111827;color:#e5e7eb;border:1px solid #334155;border-radius:18px;padding:24px;box-shadow:0 24px 80px rgba(0,0,0,.48);font-family:Outfit,Inter,sans-serif}
    .mh-auth-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px}.mh-auth-head h2{margin:0;font-size:22px}.mh-auth-close{border:0;background:transparent;color:#94a3b8;font-size:28px;cursor:pointer}
    .mh-auth-grid{display:grid;gap:14px}.mh-auth-field{display:grid;gap:7px}.mh-auth-field label{font-size:13px;color:#94a3b8}.mh-auth-field input,.mh-auth-field select{width:100%;box-sizing:border-box;border:1px solid #334155;border-radius:10px;background:#0b1220;color:#f8fafc;padding:11px 12px;font:inherit;outline:none}.mh-auth-field input:focus,.mh-auth-field select:focus{border-color:#38bdf8;box-shadow:0 0 0 3px rgba(56,189,248,.12)}
    .mh-auth-row{display:grid;grid-template-columns:1fr 1fr;gap:12px}.mh-auth-help{padding:12px 14px;border-radius:10px;background:#0b1220;color:#cbd5e1;font-size:13px;line-height:1.55}.mh-auth-help strong{color:#f8fafc}.mh-auth-error{display:none;padding:10px 12px;border-radius:10px;background:rgba(239,68,68,.12);color:#fca5a5;font-size:13px}.mh-auth-error.show{display:block}
    .mh-auth-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:4px}.mh-auth-btn{border:1px solid #334155;border-radius:10px;padding:10px 15px;background:#172033;color:#e5e7eb;font:inherit;font-weight:600;cursor:pointer}.mh-auth-btn.primary{background:#0284c7;border-color:#0284c7;color:white}.mh-auth-btn:disabled{opacity:.55;cursor:not-allowed}.mh-auth-link{border:0;background:transparent;color:#7dd3fc;padding:8px 0;cursor:pointer;text-align:left}
    .mh-auth-guide{display:grid;gap:10px}.mh-auth-step{display:flex;align-items:flex-start;gap:10px;color:#cbd5e1;font-size:13px;line-height:1.5}.mh-auth-step input{width:16px;height:16px;margin-top:2px;accent-color:#0284c7}.mh-auth-privacy{color:#94a3b8;font-size:12px;line-height:1.5}
    .mh-auth-device{display:grid;gap:12px;text-align:center;padding:18px;border:1px solid #334155;border-radius:12px;background:#0b1220}.mh-auth-code{font:700 28px/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.12em;color:#f8fafc}.mh-auth-muted{font-size:12px;color:#94a3b8}
    @media(max-width:620px){.mh-auth-card{padding:18px}.mh-auth-row{grid-template-columns:1fr}}
  `;

  let providers = null;
  let activeModal = null;
  let deviceCancelled = false;

  function installStyle() {
    if (document.getElementById('mh-auth-style')) return;
    const style = document.createElement('style');
    style.id = 'mh-auth-style';
    style.textContent = STYLE;
    document.head.appendChild(style);
  }

  async function request(path, options = {}) {
    const init = {credentials: 'same-origin', ...options};
    init.headers = {'Content-Type': 'application/json', ...(options.headers || {})};
    const response = await fetch(path, init);
    let data = {};
    try { data = await response.json(); } catch (_) {}
    if (!response.ok) throw new Error(data.detail || data.message || `请求失败（${response.status}）`);
    return data;
  }

  function createNode(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (key === 'className') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'hidden') node.hidden = Boolean(value);
      else if (key === 'value') node.value = value;
      else if (value !== undefined && value !== null) node.setAttribute(key, String(value));
    }
    for (const child of children) {
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function field(labelText, input) {
    return createNode('div', {className: 'mh-auth-field'}, [
      createNode('label', {for: input.id, text: labelText}),
      input,
    ]);
  }

  function closeModal() {
    deviceCancelled = true;
    if (activeModal) activeModal.remove();
    activeModal = null;
  }

  function showError(message) {
    const node = activeModal?.querySelector('.mh-auth-error');
    if (!node) return;
    node.textContent = message;
    node.classList.add('show');
  }

  function clearError() {
    const node = activeModal?.querySelector('.mh-auth-error');
    if (!node) return;
    node.textContent = '';
    node.classList.remove('show');
  }

  function detectProvider(email) {
    const domain = String(email).trim().toLowerCase().split('@').pop();
    for (const [key, provider] of Object.entries(providers || {})) {
      if ((provider.domains || []).includes(domain)) return key;
    }
    return 'custom';
  }

  function formData() {
    const root = activeModal;
    return {
      provider: root.querySelector('#mh-provider').value,
      email: root.querySelector('#mh-email').value.trim().toLowerCase(),
      name: root.querySelector('#mh-name').value.trim(),
      poll_interval: Number(root.querySelector('#mh-interval').value || 300),
      color: root.querySelector('#mh-color').value || '#38bdf8',
      secret: root.querySelector('#mh-secret')?.value || '',
      imap_host: root.querySelector('#mh-host')?.value.trim() || '',
      imap_port: Number(root.querySelector('#mh-port')?.value || 993),
    };
  }

  function renderAuthMode(forcePassword = false) {
    if (!activeModal) return;
    const key = activeModal.querySelector('#mh-provider').value;
    const provider = providers[key];
    const authBox = activeModal.querySelector('#mh-auth-mode');
    const canBrowser = Boolean(provider.oauth?.browser);
    const canDevice = Boolean(provider.oauth?.device);
    const canPassword = (provider.auth_modes || []).some(
      mode => mode === 'password' || mode === 'app_password'
    );
    const useOAuth = !forcePassword && (canBrowser || canDevice);
    activeModal.dataset.forcePassword = forcePassword ? '1' : '0';

    const help = createNode('div', {className: 'mh-auth-help'}, [
      createNode('strong', {text: provider.label}),
      createNode('br'),
      document.createTextNode(provider.help || ''),
    ]);
    const secretInput = createNode('input', {
      id: 'mh-secret', type: 'password', autocomplete: 'new-password',
      placeholder: '不会在页面中回显',
    });
    const secretField = field(provider.secret_label || '登录凭据', secretInput);
    secretField.id = 'mh-secret-wrap';
    secretField.hidden = useOAuth;

    const hostInput = createNode('input', {id: 'mh-host', placeholder: 'imap.example.com'});
    const portInput = createNode('input', {
      id: 'mh-port', type: 'number', min: '1', max: '65535', value: '993',
    });
    const customFields = createNode('div', {className: 'mh-auth-row'}, [
      field('IMAP SSL 服务器', hostInput),
      field('端口', portInput),
    ]);
    customFields.id = 'mh-custom-wrap';
    customFields.hidden = key !== 'custom';

    const actions = createNode('div', {className: 'mh-auth-actions'});
    if (provider.guided_auth === 'netease_app_password' && !useOAuth) {
      renderNeteaseGuide(authBox, help, secretField, customFields, actions, provider);
      return;
    }
    if (useOAuth) {
      const brand = key === 'gmail' ? 'Google' : (['163', '126', 'yeah'].includes(key) ? '网易' : 'Microsoft');
      const login = createNode('button', {
        className: 'mh-auth-btn primary', type: 'button', text: `使用 ${brand} 登录`,
      });
      login.addEventListener('click', () => {
        clearError();
        if (canBrowser) startBrowserOAuth();
        else startDeviceOAuth();
      });
      actions.append(login);
    } else {
      const save = createNode('button', {
        className: 'mh-auth-btn primary', type: 'button', text: '验证并添加',
      });
      save.addEventListener('click', savePasswordAccount);
      actions.append(save);
    }
    if (useOAuth && canPassword) {
      const fallback = createNode('button', {
        className: 'mh-auth-link', type: 'button', text: '改用应用专用密码',
      });
      fallback.addEventListener('click', () => renderAuthMode(true));
      actions.append(fallback);
    }
    if (forcePassword && (canBrowser || canDevice)) {
      const oauth = createNode('button', {
        className: 'mh-auth-link', type: 'button', text: '返回服务商登录',
      });
      oauth.addEventListener('click', () => renderAuthMode(false));
      actions.append(oauth);
    }
    if (provider.setup_url) {
      actions.append(createNode('a', {
        className: 'mh-auth-btn',
        href: provider.setup_url,
        target: '_blank',
        rel: 'noopener',
        text: provider.setup_label || '登录服务商网页完成客户端设置',
      }));
    }
    authBox.replaceChildren(help, secretField, customFields, actions);
  }

  function renderNeteaseGuide(authBox, help, secretField, customFields, actions, provider) {
    const officialLogin = createNode('a', {
      className: 'mh-auth-btn primary', href: provider.setup_url,
      target: '_blank', rel: 'noopener', text: '前往网易官方设置',
    });
    actions.append(officialLogin);

    const checks = [
      '我已在官方邮箱设置中开启 IMAP/SMTP 服务',
      '我已生成客户端授权密码（不是网页登录密码）',
    ].map((text, index) => {
      const checkbox = createNode('input', {type: 'checkbox', id: `mh-netease-step-${index}`});
      return {checkbox, node: createNode('label', {className: 'mh-auth-step', for: checkbox.id}, [checkbox, text])};
    });
    const guide = createNode('div', {className: 'mh-auth-guide'}, [
      createNode('div', {className: 'mh-auth-privacy', text: 'MailHub 不会读取或保存你的网易网页登录密码。网易目前未向普通第三方 IMAP 客户端开放 OAuth Token 登录。'}),
      ...checks.map(item => item.node),
    ]);

    const secretInput = secretField.querySelector('#mh-secret');
    secretInput.disabled = true;
    secretInput.placeholder = '完成上方步骤后填写授权密码';
    const save = createNode('button', {
      className: 'mh-auth-btn primary', type: 'button', text: '验证授权并添加', disabled: 'disabled',
    });
    save.addEventListener('click', savePasswordAccount);
    actions.append(save);
    const updateState = () => {
      const ready = checks.every(item => item.checkbox.checked);
      secretInput.disabled = !ready;
      save.disabled = !ready;
      if (ready) secretInput.focus();
    };
    checks.forEach(item => item.checkbox.addEventListener('change', updateState));
    authBox.replaceChildren(help, actions, guide, secretField, customFields);
  }

  async function startBrowserOAuth() {
    const data = formData();
    if (!data.email || !data.email.includes('@')) return showError('请先填写完整邮箱地址');
    const popup = window.open('about:blank', 'mailhub-oauth-login', 'popup=yes,width=560,height=720');
    try {
      const result = await request('/api/oauth/start', {
        method: 'POST',
        body: JSON.stringify(data),
      });
      if (popup) popup.location.replace(result.authorization_url);
      else {
        const link = createNode('a', {
          href: result.authorization_url,
          target: '_blank',
          rel: 'noopener',
          text: '点此继续登录',
        });
        const notice = createNode('p', {className: 'mh-auth-help'}, [
          '浏览器阻止了弹窗。', link,
        ]);
        activeModal.querySelector('#mh-auth-mode').append(notice);
      }
    } catch (error) {
      if (popup) popup.close();
      showError(error.message);
    }
  }

  async function startDeviceOAuth() {
    const data = formData();
    if (!data.email || !data.email.includes('@')) return showError('请先填写完整邮箱地址');
    try {
      const result = await request('/api/oauth/outlook/device/start', {
        method: 'POST',
        body: JSON.stringify(data),
      });
      const url = result.verification_uri_complete || result.verification_uri;
      const authMode = activeModal.querySelector('#mh-auth-mode');
      const status = createNode('div', {
        className: 'mh-auth-muted', id: 'mh-device-status', text: '等待登录完成…',
      });
      const openLogin = createNode('a', {
        className: 'mh-auth-btn primary', href: url, target: '_blank', rel: 'noopener',
        text: '打开 Microsoft 登录',
      });
      authMode.replaceChildren(createNode('div', {className: 'mh-auth-device'}, [
        createNode('div', {text: '在 Microsoft 登录页确认此账户'}),
        createNode('div', {className: 'mh-auth-code', text: result.user_code}),
        openLogin,
        status,
      ]));
      window.open(url, 'mailhub-ms-device', 'popup=yes,width=560,height=720');
      deviceCancelled = false;
      await pollDevice(result.transaction_id, Number(result.interval || 5), Date.now() + Number(result.expires_in || 900) * 1000);
    } catch (error) {
      showError(error.message);
    }
  }

  async function pollDevice(transactionId, interval, deadline) {
    while (!deviceCancelled && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, Math.max(1, interval) * 1000));
      if (deviceCancelled) return;
      try {
        const result = await request('/api/oauth/outlook/device/poll', {
          method: 'POST',
          body: JSON.stringify({transaction_id: transactionId}),
        });
        if (result.pending) {
          interval = Number(result.interval || interval);
          continue;
        }
        if (result.ok) {
          const status = activeModal?.querySelector('#mh-device-status');
          if (status) status.textContent = '登录成功，正在刷新账户…';
          setTimeout(() => location.reload(), 500);
          return;
        }
      } catch (error) {
        showError(error.message);
        return;
      }
    }
    if (!deviceCancelled) showError('登录已超时，请重新发起');
  }

  async function savePasswordAccount() {
    const data = formData();
    if (!data.email || !data.email.includes('@')) return showError('请填写完整邮箱地址');
    if (!data.secret) return showError('请填写服务商要求的登录凭据');
    if (data.provider === 'custom' && !data.imap_host) return showError('请填写 IMAP 服务器地址');
    try {
      const accounts = await request('/api/accounts');
      const existing = accounts.find(item => item.provider === data.provider && item.email.toLowerCase() === data.email);
      if (existing) {
        await request(`/api/accounts/${existing.id}`, {method: 'PUT', body: JSON.stringify(data)});
      } else {
        await request('/api/accounts', {method: 'POST', body: JSON.stringify(data)});
      }
      closeModal();
      location.reload();
    } catch (error) {
      showError(error.message);
    }
  }

  async function openWizard() {
    installStyle();
    deviceCancelled = true;
    try {
      providers = (await request('/api/oauth/providers')).providers;
    } catch (error) {
      alert(error.message);
      return;
    }
    closeModal();
    deviceCancelled = false;
    const backdrop = createNode('div', {className: 'mh-auth-backdrop'});
    const providerSelect = createNode('select', {id: 'mh-provider'});
    for (const [key, value] of Object.entries(providers)) {
      providerSelect.append(createNode('option', {value: key, text: value.label}));
    }
    const emailInput = createNode('input', {
      id: 'mh-email', type: 'email', autocomplete: 'username', placeholder: 'name@example.com',
    });
    const nameInput = createNode('input', {id: 'mh-name', placeholder: '可选'});
    const intervalSelect = createNode('select', {id: 'mh-interval'});
    for (const [value, label] of [['60', '1 分钟'], ['300', '5 分钟'], ['900', '15 分钟'], ['3600', '1 小时']]) {
      const option = createNode('option', {value, text: label});
      option.selected = value === '300';
      intervalSelect.append(option);
    }
    const colorInput = createNode('input', {id: 'mh-color', type: 'color', value: '#38bdf8'});
    const close = createNode('button', {
      className: 'mh-auth-close', type: 'button', 'aria-label': '关闭', text: '×',
    });
    const card = createNode('section', {
      className: 'mh-auth-card', role: 'dialog', 'aria-modal': 'true',
      'aria-labelledby': 'mh-auth-title',
    }, [
      createNode('div', {className: 'mh-auth-head'}, [
        createNode('h2', {id: 'mh-auth-title', text: '登录邮箱账户'}),
        close,
      ]),
      createNode('div', {className: 'mh-auth-grid'}, [
        field('邮箱地址', emailInput),
        createNode('div', {className: 'mh-auth-row'}, [
          field('服务商', providerSelect), field('显示名称', nameInput),
        ]),
        createNode('div', {className: 'mh-auth-row'}, [
          field('同步间隔', intervalSelect), field('账户颜色', colorInput),
        ]),
        createNode('div', {className: 'mh-auth-error', role: 'alert'}),
        createNode('div', {id: 'mh-auth-mode'}),
      ]),
    ]);
    backdrop.append(card);
    document.body.appendChild(backdrop);
    activeModal = backdrop;
    close.addEventListener('click', closeModal);
    backdrop.addEventListener('click', event => { if (event.target === backdrop) closeModal(); });
    emailInput.addEventListener('blur', () => {
      if (emailInput.value.includes('@')) {
        providerSelect.value = detectProvider(emailInput.value);
        renderAuthMode(false);
      }
    });
    providerSelect.addEventListener('change', () => renderAuthMode(false));
    renderAuthMode(false);
    emailInput.focus();
  }

  function bindExistingAddButtons() {
    const buttons = [...document.querySelectorAll('button[onclick*="accountModal"],button')];
    for (const button of buttons) {
      if (button.dataset.mhSmartBound || button.closest('.mh-auth-card')) continue;
      const inline = button.getAttribute('onclick') || '';
      const text = button.textContent.trim();
      if (!inline.includes('accountModal') && !/添加(邮箱|账户)|新增(邮箱|账户)/.test(text)) continue;
      button.dataset.mhSmartBound = '1';
      button.textContent = '登录邮箱';
      button.addEventListener('click', event => {
        event.preventDefault();
        event.stopImmediatePropagation();
        openWizard();
      }, true);
    }
  }

  window.addEventListener('message', event => {
    if (event.origin !== window.location.origin || event.data?.type !== 'mailhub-oauth') return;
    if (event.data.ok) {
      closeModal();
      location.reload();
    } else {
      showError(event.data.message || '邮箱登录失败');
    }
  });

  installStyle();
  bindExistingAddButtons();
  new MutationObserver(bindExistingAddButtons).observe(document.documentElement, {childList: true, subtree: true});
  window.mailhubAccountLogin = openWizard;
})();
