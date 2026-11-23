// 号源查询和确认的本机演示适配。实际接入时由院方的号源、订单和支付状态驱动。
window.BookingProvider = {
  mode: 'demo',

  async listSlots({date,period}) {
    const slots = [
      {id:'morning-1',period:'上午',time:'09:00–09:30',fee:30},
      {id:'morning-2',period:'上午',time:'10:00–10:30',fee:30},
      {id:'afternoon-1',period:'下午',time:'15:00–15:30',fee:30}
    ];
    return slots.filter(slot=>!period || slot.period===period).map(slot=>({...slot,date}));
  },

  async confirm({query,slotId}) {
    const slots = await this.listSlots(query);
    const slot = slots.find(item=>item.id===slotId);
    if (!slot) throw new Error('演示时段已失效');
    return {status:'demo-confirmed',slot};
  }
};
