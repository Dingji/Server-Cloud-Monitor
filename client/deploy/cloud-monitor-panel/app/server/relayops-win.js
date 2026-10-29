'use strict';
function bad(msg){const e=new Error(msg);e.status=400;throw e;}
function ok(o,x){return Object.assign({ok:true,output:o==null?'':String(o)},x||{});}
async function rateLimit(){return{present:false,zones:[],applied:[],byHour:[],statusCounts:{},errorCount:0,jails:[],conf:'MISSING'};}
async function auditSearch(){return{items:[],total:0,keys:[]};}
async function healthAlerts(){return{available:false,reason:'Relay ops are not deployed on this Windows host',checks:[],summary:{total:0,failing:0,ok:0},failingNames:[],log:[],backup:null,verify:null,dbguard:null,diskguard:null,smtp:{to:[],host:'',port:0,ssl:false,user:'',from:''},timers:[],units:[],sources:{}};}
async function relayBackups(){return{dir:'',items:[]};}
async function alertTestMail(){return ok('N/A on Windows');}
async function relayBackupRun(){return ok('N/A on Windows');}
async function relayBackupDelete(){bad('N/A on Windows');}
function relayBackupPath(){return null;}
module.exports={rateLimit,auditSearch,healthAlerts,relayBackups,alertTestMail,relayBackupRun,relayBackupDelete,relayBackupPath,AUDIT_KEYS:[],RELAY_BACKUP_DIR:''};
