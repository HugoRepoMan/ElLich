/*
 * CONTEXTO INICIAL
 * Reconstruye el estado de un campo a partir de los eventos recibidos por el dashboard.
 * applyEvent es una función pura: recibe el estado anterior y un evento, y devuelve
 * otro estado con texto, selección y marca de reconstrucción provisional.
 * KEY_DOWN adelanta la escritura y el movimiento del cursor; los snapshots INPUT,
 * FOCUS y BLUR corrigen el resultado real, incluido pegado, IME y deshacer.
 * Los valores sensibles se representan con una máscara fija.
 */
/** Reductor puro. INPUT/snapshots son la fuente de reconstrucción; keydown da una vista provisional. */
export function applyEvent(previous, e) {
    const s = {...(previous || {value:'', anchor:0, cursor:0, provisional:false, known:false})};
    if (e.value === '[REDACTED]') return {...s, value:'•••• [contenido excluido]', known:true, provisional:false};
    if (['INPUT','FOCUS','BLUR'].includes(e.type) && e.value !== null) {
        return {...s, value:e.value, anchor:e.selectionStart ?? 0, cursor:e.selectionEnd ?? 0,
            known:true, provisional:false};
    }
    if (e.type === 'CHANGE') return {...s, value:e.value ?? '', known:true, provisional:false};
    if (e.type !== 'KEY_DOWN' || !s.known) return s;
    const key = e.key || '';
    const lo = Math.min(s.anchor, s.cursor), hi = Math.max(s.anchor, s.cursor);
    const shift = !!(e.modifiers & 1), shortcut = !!(e.modifiers & (2 | 4 | 8)) && !(e.modifiers & 16);
    const move = pos => { s.cursor = pos; if (!shift) s.anchor = pos; };
    const left = pos => pos > 1 && /[\uDC00-\uDFFF]/.test(s.value[pos-1]) ? pos-2 : Math.max(0,pos-1);
    const right = pos => pos < s.value.length-1 && /[\uD800-\uDBFF]/.test(s.value[pos]) ? pos+2 : Math.min(s.value.length,pos+1);
    const replace = (a,b,text) => { s.value = s.value.slice(0,a) + text + s.value.slice(b); s.cursor = s.anchor = a + text.length; };
    if (shortcut && key.toLowerCase() === 'a') { s.anchor=0; s.cursor=s.value.length; }
    else if (shortcut) return s; // El siguiente INPUT corrige Ctrl+Backspace, pegar, deshacer y atajos del SO.
    else if (key === 'ArrowLeft') move(!shift && lo !== hi ? lo : left(s.cursor));
    else if (key === 'ArrowRight') move(!shift && lo !== hi ? hi : right(s.cursor));
    else if (key === 'Home') move(0);
    else if (key === 'End') move(s.value.length);
    else if (key === 'Backspace') replace(lo === hi ? left(lo) : lo, hi, '');
    else if (key === 'Delete') replace(lo, lo === hi ? right(hi) : hi, '');
    else if (key === 'Enter' && e.elementId === 'demo-notes') replace(lo,hi,'\n');
    else if ([...key].length === 1) replace(lo,hi,key); // event.key ya contiene mayúscula, AltGr y distribución del teclado.
    else return s;
    return {...s, provisional:true};
}
