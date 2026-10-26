/*
 * CONTEXTO INICIAL
 * Capturador web activado por el usuario desde el panel de la página.
 * Solicita un contexto ligado a un agente y escucha teclado, input, clics, selecciones
 * y foco mediante delegación. Sólo admite elementos autorizados y excluye contraseñas
 * antes de leer su valor. Los snapshots conservan texto y selección para reconstruirlos.
 * Acumula eventos en una cola limitada, envía lotes cada 400 ms y reintenta el mismo
 * cuerpo hasta obtener confirmación. El heartbeat conserva el cupo de una captura abierta;
 * al salir intenta drenarlo y revocarlo. No captura otras aplicaciones del sistema operativo.
 */
/* Captura explícita en esta página. Sin listeners globales del SO ni almacenamiento persistente. */
(() => {
    'use strict';
    const panel = document.querySelector('[data-audit-panel]');
    if (!panel) return;
    const start = panel.querySelector('[data-audit-start]');
    const stop = panel.querySelector('[data-audit-stop]');
    const status = panel.querySelector('[data-audit-status]');
    const base = panel.dataset.auditApi;
    const agentSelect = panel.querySelector('[data-audit-agent-select]');
    const unlockAgent = () => { if (agentSelect) agentSelect.disabled = false; };
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const MAX_QUEUE = 512, MAX_BATCH = 64;
    let grant = null, capturing = false, starting = false, queue = [], pending = null;
    let sequence = 0, busy = false, heartbeatBusy = false, retryAt = 0, failures = 0, dropped = 0;
    let lastHeartbeat = 0;
    const lastClicks = new WeakMap();
    const priority = e => ({CLICK: 4, CHANGE: 4, INPUT: 3, KEY_DOWN: 2}[e.type] || 1);
    const headers = () => ({'Content-Type': 'application/json', [csrfHeader]: csrf});
    const say = message => { status.textContent = message; };
    function approved(target) {
        if (!capturing || !grant || !(target instanceof Element)) return null;
        if (target.closest('[data-audit-private]')) return null;
        const el = target.closest('[data-audit-id]');
        const id = el?.dataset.auditId;
        if (!id || !Object.hasOwn(grant.fields, id)) return null;
        // Nunca leer el valor de contraseñas, autocomplete sensible o un campo privado.
        const sensitive = el.matches('input[type="password"], [autocomplete="current-password"], [autocomplete="new-password"], [autocomplete="one-time-code"]');
        return {el, id, mode: sensitive ? 'REDACTED' : grant.fields[id]};
    }
    function enqueue(event) {
        event.seq = ++sequence;
        event.clientTime = Date.now();
        if (queue.length >= MAX_QUEUE) {
            let victim = 0;
            for (let i = 1; i < queue.length; i++) if (priority(queue[i]) < priority(queue[victim])) victim = i;
            dropped++;
            if (priority(queue[victim]) > priority(event)) { say('Captura activa; eventos omitidos: ' + dropped); return; }
            queue.splice(victim, 1);
        }
        queue.push(event);
        if (dropped) say('Captura activa; eventos omitidos: ' + dropped + '. La reconstrucción puede ser parcial.');
    }
    function record(type, native) {
        const context = approved(native.target);
        if (!context || !native.isTrusted) return; // Higiene local; no es una prueba para el servidor.
        const {el, id, mode} = context;
        if (mode === 'REDACTED' && type !== 'FOCUS' && type !== 'BLUR') return;
        if (['KEY_DOWN', 'KEY_UP', 'INPUT'].includes(type) && mode !== 'TEXT') return;
        if (type === 'CLICK' && !['ACTION', 'CONTROL'].includes(mode)) return;
        if (type === 'CHANGE' && mode !== 'CONTROL') return;
        const event = {type, elementId: id, key: null, value: null, modifiers: 0,
            selectionStart: null, selectionEnd: null, repeat: false};
        if (mode === 'REDACTED') { event.value = '[REDACTED]'; enqueue(event); return; }
        event.modifiers = (native.shiftKey ? 1 : 0) | (native.ctrlKey ? 2 : 0) |
            (native.altKey ? 4 : 0) | (native.metaKey ? 8 : 0) |
            (native.getModifierState?.('AltGraph') ? 16 : 0);
        if (type === 'KEY_DOWN' || type === 'KEY_UP') {
            event.key = native.key; event.repeat = native.repeat;
            // Teclas de función desconocidas no invalidan todo el lote.
            const special = ['Enter','Backspace','Delete','Tab','Escape','ArrowLeft','ArrowRight','ArrowUp','ArrowDown',
                'Home','End','PageUp','PageDown','Shift','Control','Alt','Meta','AltGraph','CapsLock','Dead','Unidentified'];
            if ([...event.key].length !== 1 && !special.includes(event.key)) return;
        }
        if (mode === 'TEXT' && ['INPUT', 'FOCUS', 'BLUR'].includes(type)) {
            // input cubre pegar, autocompletado, selección, deshacer e IME. Snapshot corrige keydown provisional.
            event.value = el.value.slice(0, 512);
            event.selectionStart = Math.min(el.selectionStart ?? event.value.length, event.value.length);
            event.selectionEnd = Math.min(el.selectionEnd ?? event.value.length, event.value.length);
        }
        if (type === 'CHANGE') event.value = (el.type === 'radio' || el.type === 'checkbox') ? String(el.checked) : el.value.slice(0, 512);
        enqueue(event);
    }
    // Delegación: una suscripción por tipo para toda la página; ningún mousemove.
    for (const [dom, type] of Object.entries({keydown:'KEY_DOWN', keyup:'KEY_UP', input:'INPUT', change:'CHANGE', focusin:'FOCUS', focusout:'BLUR'})) {
        document.addEventListener(dom, e => record(type, e), true);
    }
    document.addEventListener('click', e => {
        const context = approved(e.target);
        if (!context || !e.isTrusted) return;
        const previous = lastClicks.get(context.el);
        if (previous !== undefined && performance.now() - previous < 80) return; // Debounce de duplicados; no retrasa el submit.
        lastClicks.set(context.el, performance.now());
        record('CLICK', e);
    }, true);
    let selectionTimer;
    document.addEventListener('selectionchange', () => {
        clearTimeout(selectionTimer);
        selectionTimer = setTimeout(() => {
            const el = document.activeElement;
            const c = approved(el);
            if (c?.mode === 'TEXT') record('INPUT', {target: el, isTrusted: true});
        }, 40);
    });
    async function revokeIfDone() {
        if (!capturing && grant && !pending && !queue.length) {
            const id = grant.contextId;
            grant = null;
            await fetch(base + '/context/' + id, {method:'DELETE', headers: headers(), keepalive:true}).catch(() => {});
            start.disabled = false; unlockAgent();
            say('Captura detenida. Lotes confirmados por el servidor. Omitidos: ' + dropped);
        }
    }
    async function flush() {
        if (!grant || busy || heartbeatBusy || Date.now() < retryAt) return;
        if (!pending && queue.length) {
            const batch = {contextId: grant.contextId, batchId: crypto.randomUUID(), events: []};
            const encoder = new TextEncoder();
            for (let i = 0; i < MAX_BATCH && queue.length; i++) {
                batch.events.push(queue[0]);
                if (encoder.encode(JSON.stringify(batch)).length > 60000) { batch.events.pop(); break; }
                queue.shift();
            }
            pending = {batch, body: JSON.stringify(batch)};
        }
        if (!pending) { await revokeIfDone(); return; }
        busy = true;
        try {
            const response = await fetch(base + '/batch', {method:'POST', headers: headers(), body: pending.body, keepalive: true});
            if (!response.ok) {
                if (response.status !== 429 && response.status < 500) {
                    const lost = queue.length + pending.batch.events.length;
                    capturing = false; grant = null; queue = []; pending = null;
                    stop.disabled = true; start.disabled = false; unlockAgent();
                    say('Captura detenida por HTTP ' + response.status + '. Sin confirmar: ' + lost + '. Inicia una nueva captura.');
                    return;
                }
                throw Error('HTTP ' + response.status);
            }
            const ack = await response.json();
            if (ack.batchId !== pending.batch.batchId || ack.lastSequence !== pending.batch.events.at(-1).seq)
                throw Error('Confirmación inválida');
            pending = null; failures = 0; retryAt = 0;
            if (capturing && !dropped) say('Captura activa · último lote persistido: secuencia ' + ack.lastSequence);
        } catch (error) {
            retryAt = Date.now() + Math.min(30000, 500 * 2 ** Math.min(++failures, 6)) + Math.random() * 250;
            say('Conexión pendiente; se reintentará el mismo lote. Cola: ' + queue.length + '. Omitidos: ' + dropped);
        } finally { busy = false; }
        await revokeIfDone();
    }
    async function heartbeat() {
        if (!grant || !capturing || busy || heartbeatBusy || Date.now() - lastHeartbeat < 20000) return;
        heartbeatBusy = true;
        const id = grant.contextId;
        try {
            const response = await fetch(base + '/context/' + id + '/heartbeat', {method:'POST', headers:headers()});
            if (response.ok) lastHeartbeat = Date.now();
            else if ([401,403,404,409].includes(response.status) && grant?.contextId === id) {
                const unconfirmed = queue.length + (pending?.batch.events.length || 0);
                capturing = false; grant = null; queue = []; pending = null;
                stop.disabled = true; start.disabled = false; unlockAgent();
                say('La captura caducó o la sesión terminó. Sin confirmar: ' + unconfirmed + '. Activa una nueva captura.');
            }
        } catch { /* Un fallo de red no confirma ni descarta el lote pendiente. */ }
        finally { heartbeatBusy = false; if (!capturing) flush(); }
    }
    start.addEventListener('click', async () => {
        if (starting || grant) return;
        const agentCode = panel.dataset.auditAgent || agentSelect?.value;
        if (!agentCode) { say('Selecciona un agente. Si todavía no existe, guarda primero su registro.'); return; }
        starting = true; start.disabled = true;
        if (agentSelect) agentSelect.disabled = true;
        try {
            const response = await fetch(base + '/context?page=' + encodeURIComponent(panel.dataset.auditPage) + '&agentCode=' + encodeURIComponent(agentCode), {method:'POST', headers: headers()});
            if (!response.ok) {
                let message = 'HTTP ' + response.status;
                try { const problem = await response.json(); if (problem.error) message = problem.error + ' (HTTP ' + response.status + ')'; } catch { }
                throw Error(message);
            }
            grant = await response.json(); sequence = 0; dropped = 0; queue = []; pending = null;
            failures = 0; retryAt = 0; lastHeartbeat = Date.now(); capturing = true; stop.disabled = false;
            say('Captura activa en campos señalados. Las contraseñas están excluidas.');
            const el = document.activeElement;
            if (approved(el)) record('FOCUS', {target:el, isTrusted:true});
        } catch (error) { start.disabled = false; unlockAgent(); say('No se pudo activar: ' + error.message); }
        finally { starting = false; }
    });
    stop.addEventListener('click', () => { capturing = false; stop.disabled = true; say('Detenida; enviando eventos pendientes…'); flush(); });
    setInterval(flush, 400);
    document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
    setInterval(heartbeat, 20000);
    window.addEventListener('pagehide', () => {
        // Detener admisión y drenar antes de revocar. keepalive ayuda al abandonar la página;
        // la concesión de dos minutos libera el cupo si el navegador no entrega el cierre.
        capturing = false;
        clearTimeout(selectionTimer);
        flush();
    });
    // Una navegación puede perder eventos aún en memoria. Guardar el formulario usa AJAX para esperar el drenaje.
    const form = document.querySelector('[data-audit-form]');
    form?.addEventListener('submit', async e => {
        e.preventDefault();
        const button = e.submitter;
        if (button) button.disabled = true;
        try {
            const response = await fetch(form.action, {method:'POST', body:new URLSearchParams(new FormData(form)), headers:{[csrfHeader]:csrf}});
            if (!response.ok) throw Error('HTTP ' + response.status);
            say('Agente guardado. Operación confirmada por el servidor.');
            // Actualiza sólo la tabla: evita desmontar la captura y perder la cola al navegar.
            const html = new DOMParser().parseFromString(await response.text(), 'text/html');
            const updated = html.querySelector('[data-agent-table]');
            if (updated) document.querySelector('[data-agent-table]').replaceWith(updated);
            const updatedSelector = html.querySelector('[data-audit-agent-select]');
            if (agentSelect && updatedSelector) {
                const selected = agentSelect.value;
                agentSelect.replaceChildren(...[...updatedSelector.options].map(option => option.cloneNode(true)));
                if ([...agentSelect.options].some(option => option.value === selected)) agentSelect.value = selected;
            }
            flush();
        } catch (error) { say('Error al guardar el agente: ' + error.message); }
        finally { if (button) button.disabled = false; }
    });
})();
