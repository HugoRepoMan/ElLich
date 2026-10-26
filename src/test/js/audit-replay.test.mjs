/*
 * CONTEXTO INICIAL
 * Pruebas del reconstructor puro de texto con el ejecutor de pruebas de Node.
 * Aplican snapshots y teclas a un buffer para comprobar selección, modificadores,
 * Unicode, Enter y máscaras. Verifican que los snapshots corrigen la escritura
 * provisional y no duplican caracteres. No necesitan DOM, servidor ni base de datos.
 */
import {readFile} from 'node:fs/promises';
import {strict as assert} from 'node:assert';
import test from 'node:test';
const source = await readFile(new URL('../../main/resources/static/js/audit-replay.js',import.meta.url),'utf8');
const {applyEvent} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const snapshot = (value,start=value.length,end=start) => ({type:'INPUT',value,selectionStart:start,selectionEnd:end,elementId:'demo-notes'});
const key = (key,modifiers=0) => ({type:'KEY_DOWN',key,modifiers,elementId:'demo-notes'});
test('shift, selección, borrado y flechas respetan el buffer', () => {
    let s=applyEvent(null,snapshot('hola'));
    s=applyEvent(s,key('ArrowLeft',1));
    assert.equal(s.cursor,3); assert.equal(s.anchor,4);
    s=applyEvent(s,key('A',1)); assert.equal(s.value,'holA');
    s=applyEvent(s,key('Backspace')); assert.equal(s.value,'hol');
    s=applyEvent(s,key('Home')); s=applyEvent(s,key('Delete')); assert.equal(s.value,'ol');
});
test('atajos Ctrl/Alt/Meta no insertan letras; AltGr utiliza event.key', () => {
    let s=applyEvent(null,snapshot('texto'));
    for (const mod of [2,4,8]) { s=applyEvent(s,key('z',mod)); assert.equal(s.value,'texto'); }
    s=applyEvent(s,key('@',2|4|16)); assert.equal(s.value,'texto@');
    s=applyEvent(s,key('a',2)); s=applyEvent(s,key('x')); assert.equal(s.value,'x');
});
test('snapshots corrigen pegado, IME y deshacer sin duplicar keydown', () => {
    let s=applyEvent(null,snapshot('a'));
    s=applyEvent(s,key('b')); assert.equal(s.value,'ab'); assert.equal(s.provisional,true);
    s=applyEvent(s,snapshot('ab')); assert.equal(s.value,'ab'); assert.equal(s.provisional,false);
    s=applyEvent(s,snapshot('日本語 pegado',3)); assert.equal(s.value,'日本語 pegado'); assert.equal(s.cursor,3);
});
test('Enter en textarea, Unicode y redacción de secretos', () => {
    let s=applyEvent(null,snapshot('😀'));
    s=applyEvent(s,key('Backspace')); assert.equal(s.value,'');
    s=applyEvent(s,key('Enter')); assert.equal(s.value,'\n');
    s=applyEvent(s,{type:'FOCUS',value:'[REDACTED]'});
    assert.equal(s.value,'•••• [contenido excluido]');
});
