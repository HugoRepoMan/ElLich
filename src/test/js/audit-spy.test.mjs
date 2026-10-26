/*
 * CONTEXTO INICIAL
 * Pruebas del capturador ejecutadas con Node dentro de un DOM simulado.
 * Sustituyen elementos, fetch y temporizadores para observar los lotes generados,
 * la asignación del agente, los reintentos, la privacidad y el cierre de contextos.
 * También verifican heartbeat y mensajes del límite de activaciones. Los eventos
 * simulados sólo se usan en estas pruebas y nunca se envían al PostgreSQL real.
 */
import {readFile} from 'node:fs/promises';
import {runInNewContext} from 'node:vm';
import {webcrypto} from 'node:crypto';
import {strict as assert} from 'node:assert';
import test from 'node:test';
const source = await readFile(new URL('../../main/resources/static/js/audit-spy.js',import.meta.url),'utf8');
function setup({agent='AGENT-A',selected=null,failFirst=false,grantStatus=200}={}) {
    class Element {
        constructor(id=null,sensitive=false){this.dataset=id?{auditId:id}:{};this.sensitive=sensitive;this.listeners={};this.disabled=false;this.value='';this.selectionStart=0;this.selectionEnd=0;}
        closest(selector){return selector==='[data-audit-id]'&&this.dataset.auditId?this:null;}
        matches(){return this.sensitive;}
        addEventListener(name,handler){this.listeners[name]=handler;}
    }
    const start=new Element(),stop=new Element(),status={textContent:''},agentSelect=selected===null?null:{value:selected,disabled:false};
    const panel={dataset:{auditApi:'/api/audit',auditPage:'agent-audit',auditAgent:agent},querySelector(selector){return {'[data-audit-start]':start,'[data-audit-stop]':stop,'[data-audit-status]':status,'[data-audit-agent-select]':agentSelect}[selector]??null;}};
    const handlers={},windowHandlers={},intervals=[],requests=[],batches=[];
    let clock=Date.now(),fail=failFirst;
    const document={activeElement:null,hidden:false,querySelector(selector){if(selector==='[data-audit-panel]')return panel;if(selector.includes('meta[name="_csrf_header"]'))return {content:'X-CSRF-TOKEN'};if(selector.includes('meta[name="_csrf"]'))return {content:'test-token'};return null;},addEventListener(name,handler){handlers[name]=handler;}};
    const fields={'demo-notes':'TEXT','demo-click':'ACTION','demo-choice':'CONTROL','demo-secret':'REDACTED'};
    const fetch=async(url,options)=>{
        requests.push({url,options});
        if(url.includes('/context?')&&grantStatus===429)return {ok:false,status:429,json:async()=>({error:'Ya hay cuatro capturas activas; detén una o espera dos minutos'})};
        if(url.includes('/context?'))return {ok:true,json:async()=>({contextId:'00000000-0000-0000-0000-000000000001',fields})};
        if(url.endsWith('/batch')){
            batches.push(options.body);
            if(fail){fail=false;throw Error('offline');}
            const body=JSON.parse(options.body);
            return {ok:true,json:async()=>({batchId:body.batchId,lastSequence:body.events.at(-1).seq})};
        }
        return {ok:true};
    };
    class Clock extends Date {static now(){return clock;}}
    runInNewContext(source,{document,Element,fetch,crypto:webcrypto,TextEncoder,Date:Clock,performance:{now:()=>1000},window:{addEventListener(name,handler){windowHandlers[name]=handler;}},setInterval:fn=>intervals.push(fn),setTimeout,clearTimeout,URLSearchParams,FormData});
    return {Element,start,stop,status,requests,batches,activate:()=>start.listeners.click(),heartbeat:()=>intervals[1](),leave:()=>windowHandlers.pagehide(),flush:()=>intervals[0](),advance:()=>{clock+=60000;},emit:(name,target,extra={})=>handlers[name]({target,isTrusted:true,...extra})};
}
test('captura delegada envia teclas, input y clic al agente seleccionado',async()=>{
    const app=setup();await app.activate();
    assert.match(app.requests[0].url,/agentCode=AGENT-A/);
    const field=new app.Element('demo-notes');
    app.emit('focusin',field);app.emit('keydown',field,{key:'H',shiftKey:true});
    field.value='H';field.selectionStart=field.selectionEnd=1;app.emit('input',field);
    app.emit('keyup',field,{key:'H'});app.emit('click',new app.Element('demo-click'));
    await app.flush();const batch=JSON.parse(app.batches[0]);
    assert.deepEqual(batch.events.map(e=>e.type),['FOCUS','KEY_DOWN','INPUT','KEY_UP','CLICK']);
    assert.equal(batch.events[1].modifiers,1);assert.equal(batch.events[2].value,'H');
    assert.deepEqual(batch.events.map(e=>e.seq),[1,2,3,4,5]);
});
test('una contraseña nunca se lee ni se incluye en el lote',async()=>{
    const app=setup();await app.activate();const secret=new app.Element('demo-secret',true);
    Object.defineProperty(secret,'value',{get(){throw Error('Se intento leer contraseña');}});
    app.emit('focusin',secret);app.emit('keydown',secret,{key:'x'});app.emit('input',secret);await app.flush();
    const events=JSON.parse(app.batches[0]).events;assert.equal(events.length,1);assert.equal(events[0].value,'[REDACTED]');assert.equal(events[0].key,null);
});
test('sin seleccionar agente no inicia una captura ambigua',async()=>{
    const app=setup({agent:'',selected:''});await app.activate();assert.equal(app.requests.length,0);assert.match(app.status.textContent,/Selecciona un agente/);
});
test('un fallo de red reintenta el cuerpo identico sin duplicar teclas',async()=>{
    const app=setup({failFirst:true});await app.activate();const field=new app.Element('demo-notes');app.emit('focusin',field);
    await app.flush();app.advance();await app.flush();assert.equal(app.batches.length,2);assert.equal(app.batches[0],app.batches[1]);
});

test('cerrar la página revoca un contexto sin eventos pendientes mediante keepalive',async()=>{
    const app=setup();await app.activate();app.leave();
    const request=app.requests.find(r=>r.options.method==='DELETE');
    assert.ok(request);assert.equal(request.options.keepalive,true);
});
test('una captura abierta renueva su concesion aunque el usuario no escriba',async()=>{
    const app=setup();await app.activate();app.advance();await app.heartbeat();
    assert.ok(app.requests.some(r=>r.url.endsWith('/heartbeat')&&r.options.method==='POST'));
});
test('el límite de activaciones muestra la explicación del servidor',async()=>{
    const app=setup({grantStatus:429});await app.activate();
    assert.match(app.status.textContent,/cuatro capturas activas/);assert.match(app.status.textContent,/HTTP 429/);
    assert.equal(app.start.disabled,false);
});
