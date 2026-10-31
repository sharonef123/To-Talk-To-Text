// Talk→To→Text (By Efroni) — MV3 service worker.
// Its only jobs: route user commands to the active tab and mirror state on the badge.

function setBadge(active) {
  chrome.action.setBadgeText({ text: active ? 'ON' : '' });
  if (!active) return;
  chrome.action.setBadgeBackgroundColor({ color: '#c9343a' });
  try {
    // Chrome 110+; harmless to skip on older builds.
    chrome.action.setBadgeTextColor({ color: '#ffffff' });
  } catch (_) {
    /* ignore */
  }
}

async function sendToActiveTab(tab, type) {
  let target = tab;
  if (!target || typeof target.id !== 'number') {
    try {
      const [first] = await chrome.tabs.query({ active: true, currentWindow: true });
      target = first;
    } catch (_) {
      return;
    }
  }
  if (!target) return;
  try {
    await chrome.tabs.sendMessage(target.id, { type });
  } catch (_) {
    // No content script on this page (chrome://, web store, unloaded tab).
  }
}

chrome.action.onClicked.addListener((tab) => sendToActiveTab(tab, 'toggle-dictation'));

chrome.commands.onCommand.addListener((command, tab) => {
  if (command === 'toggle-dictation') sendToActiveTab(tab, 'toggle-dictation');
  else if (command === 'toggle-language') sendToActiveTab(tab, 'toggle-language');
});

chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === 'ttt-state') setBadge(!!msg.active);
});
