import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import vm from 'node:vm';

const sandbox = {window:{}};
const moduleFile = new URL('../shared/offline-assets/EyeOffline/booking-intake.js',import.meta.url);
vm.runInNewContext(readFileSync(moduleFile,'utf8'),sandbox);
const {questions,update,assess,draft} = sandbox.window.BookingIntake;
const answer = (choice,note='')=>({choice,note});

test('每条路径均三题，各题有三到六个选项',()=>{
  const [chief] = questions({});
  for(const choice of ['',...Object.keys(chief.options)]){
    const first={chief:answer(choice)};
    const [,follow] = questions(first);
    for(const selected of ['',...Object.keys(follow.options)]){
      const path=questions({...first,[follow.id]:answer(selected)});
      assert.equal(path.length,3);
      for(const item of path)assert.ok(Object.keys(item.options).length>=3 && Object.keys(item.options).length<=6);
    }
  }
  assert.notEqual(questions({chief:answer('vision'),vision:answer('near')})[2].id,
    questions({chief:answer('vision'),vision:answer('gradual')})[2].id);
  assert.equal(questions({chief:answer('','主要是干涩')})[1].id,'surface');
});

test('换主诉或第二题分支后，丢弃失效答案及对应的就医提示',()=>{
  const filled={chief:answer('vision'),vision:answer('gradual'),visionHistory:answer('cataract','看过医生')};
  assert.equal(assess(filled).department,'白内障科');
  const updated=update(filled,questions(filled)[1],answer('near'));
  assert.equal(updated.visionHistory,undefined);
  assert.equal(questions(updated)[2].id,'nearFar');
  assert.equal(assess(updated).department,'视光科');
  const switched=update(filled,questions(filled)[0],answer('surface'));
  assert.equal(switched.vision,undefined);
  assert.equal(switched.visionHistory,undefined);
});

test('急症优先，否定与既往描述不触发已确认的急症',()=>{
  assert.equal(assess({chief:answer('vision'),vision:answer('sudden')}).urgent,true);
  assert.equal(assess({chief:answer('floaters'),floaters:answer('new')}).urgent,true);
  assert.equal(assess({chief:answer('surface'),surface:answer('contactPain')}).urgent,true);
  assert.equal(assess({chief:answer('review'),review:answer('cataract'),reviewContext:answer('postOp')}).urgent,true);
  assert.equal(assess({chief:answer('refraction','刚才突然看不见了')}).urgent,true);
  assert.equal(assess({chief:answer('surface','曾经有眼外伤，这次主要眼睛干，未出现剧烈眼痛')}).urgent,false);
  assert.equal(assess({chief:answer('floaters','没有新出现的闪光、飞蚊或黑影')}).urgent,false);
  assert.equal(assess({chief:answer('floaters'),floaters:answer('unknown')}).priority,'请尽快人工导诊');
});

test('预问诊草稿包含姓名和本次回答',()=>{
  const answers={chief:answer('surface','左眼更干'),surface:answer('dry'),dryContext:answer('screen')};
  const rows=Object.fromEntries(draft(answers,'测试姓名'));
  assert.equal(rows.就诊人,'测试姓名');
  assert.match(rows.主诉,/左眼更干/);
  assert.equal(rows.出现时机,'长时间用屏后');
  assert.equal(rows.导诊参考,'角膜及眼表科，门诊参考');
});

test('演示号源沿用选定日期和时段，拒绝未知号源',async()=>{
  const providerFile=new URL('../shared/offline-assets/EyeOffline/booking-provider.js',import.meta.url);
  vm.runInNewContext(readFileSync(providerFile,'utf8'),sandbox);
  const provider=sandbox.window.BookingProvider;
  const query={date:'2030-08-18',period:'下午'};
  const slots=await provider.listSlots(query);
  assert.equal(slots.length,1);
  assert.equal(slots[0].date,query.date);
  assert.equal(slots[0].period,'下午');
  await assert.rejects(provider.confirm({query,slotId:'morning-1'}));
  const receipt=await provider.confirm({query,slotId:slots[0].id});
  assert.equal(receipt.status,'demo-confirmed');
  assert.equal(receipt.slot.date,query.date);
});
