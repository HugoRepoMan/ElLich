/*
 * CONTEXTO INICIAL
 * Controla el feed visible y la reconstrucción de las capturas.
 * Abre un SSE y carga el historial persistido; si la plantilla indica un agente,
 * lo incluye en ambas peticiones. Deduplica registros y mantiene estados por contexto
 * para no mezclar sesiones. Cada campo se reconstruye mediante audit-replay.js.
 * Actualiza el DOM con textContent, limita la memoria y muestra avisos ante pérdidas
 * o desconexiones. Los datos del navegador siguen identificados como no verificados.
 */
import {applyEvent} from './audit-replay.js';
const root = document.querySelector('[data-audit-dashboard]');
const agentCode = root.dataset.auditAgent || null;
const api = path => root.dataset.auditApi + path + (agentCode ? '?agentCode=' + encodeURIComponent(agentCode) : '');
const empty = document.querySelector('#audit-empty');
const feed = document.querySelector('#audit-feed');
const selector = document.querySelector('#audit-context');
const reconstruction = document.querySelector('#audit-reconstruction');
const connection = document.querySelector('#audit-connection');
const warning = document.querySelector('#audit-warning');
const sessions = new Map(), seen = new Set();
let initial = true, buffered = [], recovering = false;
function warn(message) { warning.hidden = false; warning.textContent = message; }
function render() {
    reconstruction.replaceChildren();
    const session = sessions.get(selector.value);
    if (!session) { reconstruction.textContent = 'Activa la captura y escribe en los campos de prueba para ver la reconstrucción.'; return; }
    const info = document.createElement('p');
    info.textContent = session.partial ? 'Reconstrucción parcial. Los snapshots posteriores actualizan cada campo.' : 'Texto reconstruido a partir de snapshots y teclas. Una tecla provisional puede cambiar con el siguiente INPUT.';
    reconstruction.append(info);
    for (const [id,state] of session.fields) {
        const card = document.createElement('section'); card.className = 'replay-field';
        const title = document.createElement('h3'); title.textContent = id + (session.focus === id ? ' · foco' : '');
        const text = document.createElement('pre'); text.textContent = state.known ? state.value : '[sin snapshot inicial]';
        const detail = document.createElement('small');
        detail.textContent = 'Selección: ' + Math.min(state.anchor,state.cursor) + '–' + Math.max(state.anchor,state.cursor) + (state.provisional ? ' · provisional' : '');
        card.append(title,text,detail); reconstruction.append(card);
    }
}
function consume(view) {
    if (agentCode && view.agentCode !== agentCode) return;
    if (seen.has(view.id)) return;
    seen.add(view.id);
    if (seen.size > 2000) seen.delete(seen.values().next().value);
    if (empty) empty.hidden = true;
    const e = view.event;
    const item = document.createElement('li');
    item.className = view.source === 'SERVER_CONFIRMED' ? 'audit-event audit-server' :
        'audit-event ' + (e.type.startsWith('KEY') || e.type === 'INPUT' ? 'audit-key' : e.type === 'CLICK' ? 'audit-click' : 'audit-ui');
    const title = document.createElement('strong');
    const mods = ['Shift','Ctrl','Alt','Meta','AltGr'].filter((_,i) => e.modifiers & (1 << i));
    title.textContent = e.type + ' · ' + e.elementId + (e.key ? ' · ' + [...mods,e.key].join('+') : '');
    const meta = document.createElement('small');
    meta.textContent = new Date(view.receivedAt).toLocaleTimeString() + ' · ' + view.actor + ' · ' +
        (view.source === 'SERVER_CONFIRMED' ? 'CONFIRMADO POR SERVIDOR' : 'DECLARADO POR NAVEGADOR') + ' · ' + view.contextId.slice(0,8) + ' · agente: ' + (view.agentCode || 'sin asignar');
    item.append(title,meta);
    if (e.value !== null && ['INPUT','CHANGE','ACTION'].includes(e.type)) {
        const value = document.createElement('code'); value.textContent = e.value; item.append(value);
    }
    feed.prepend(item);
    while (feed.children.length > 300) feed.lastElementChild.remove();
    if (view.source === 'SERVER_CONFIRMED') return;
    let s = sessions.get(view.contextId);
    if (!s) {
        if (sessions.size >= 50) {
            const oldest = sessions.keys().next().value;
            sessions.delete(oldest);
            [...selector.options].find(o => o.value === oldest)?.remove();
        }
        s = {fields:new Map(), sequence:0, partial:e.seq > 1, focus:null}; sessions.set(view.contextId,s);
        const option = document.createElement('option'); option.value = view.contextId;
        option.textContent = view.actor + ' · ' + view.contextId.slice(0,8); selector.append(option);
    }
    if (e.seq <= s.sequence) return; // Historial solapado con SSE: no sobrescribir un snapshot nuevo con uno viejo.
    if (view.gapsBefore || (s.sequence && e.seq !== s.sequence + 1)) {
        s.partial = true; warn('Faltan eventos en una captura. Consulta PostgreSQL para el historial completo; los eventos descartados antes de persistir no son recuperables.');
    }
    s.sequence = e.seq;
    if (e.type === 'FOCUS') s.focus = e.elementId;
    if (e.type === 'BLUR' && s.focus === e.elementId) s.focus = null;
    s.fields.set(e.elementId, applyEvent(s.fields.get(e.elementId),e));
    if (selector.value === view.contextId) render();
}
async function loadHistory() {
    const response = await fetch(api('/history'), {cache:'no-store'});
    if (!response.ok) throw Error('HTTP ' + response.status);
    const history = await response.json();
    // Orden por secuencia dentro de una captura; el timestamp del cliente nunca decide el orden.
    history.reverse();
    for (const v of history) consume(v);
}
const stream = new EventSource(api('/stream'));
stream.addEventListener('ready', async () => {
    connection.textContent = 'En vivo';
    if (!initial) return;
    try { await loadHistory(); }
    catch (e) { warn('No se pudo cargar historial: ' + e.message); }
    finally { initial = false; for (const v of buffered) consume(v); buffered = []; render(); }
});
stream.addEventListener('audit', message => {
    try {
        const view = JSON.parse(message.data);
        if (initial) {
            if (buffered.length >= 256) { buffered.shift(); warn('Carga inicial incompleta por saturación.'); }
            buffered.push(view);
        } else consume(view);
    } catch { warn('Se recibió un evento inválido.'); }
});
stream.addEventListener('gap', async () => {
    warn('El canal en vivo perdió eventos o el servidor se reinició. Recuperando los últimos 200 registros; la reconstrucción puede ser parcial.');
    for (const s of sessions.values()) s.partial = true;
    if (recovering) return;
    recovering = true;
    try { await loadHistory(); render(); }
    catch { warn('No se pudo recuperar el historial.'); }
    finally { recovering = false; }
});
stream.onerror = () => { connection.textContent = 'Reconectando… Si la sesión caducó, vuelve a iniciar sesión.'; };
selector.addEventListener('change', render);
window.addEventListener('pagehide', () => stream.close());
render();
