/* The garden clock runs only when the host advances foreground play time. */
window.createSproutGarden = function createSproutGarden({
  practice = false, reducedMotion = false, random = Math.random
} = {}) {
  const portions = 16, minimumCareMs = 60000;
  const requestInterval = minimumCareMs / (portions - 1);
  const coinTimes = [20000, 40000];
  const groundY = 92, groundRest = 2000;
  const maxMaterials = practice ? 1 : 4;
  const tools = [0, 1, 2, 3];
  const items = [], coins = [];
  let elapsed = 0, deliveries = 0, collected = 0, done = false;
  let lastFedTool = null, lastFedAt = -Infinity;
  let nextDrop = 2300, minted = 0, serial = 0;
  let bag = [], need = null;

  const between = (low, high) => low + (high - low) * random();
  const milliseconds = (low, high) => Math.round(between(low, high));
  const choose = choices => choices[Math.floor(random() * choices.length)];
  function shuffled(values) {
    const copy = [...values];
    for (let i = copy.length - 1; i > 0; i--) {
      const j = Math.floor(random() * (i + 1));
      [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
  }
  function nextNeed() {
    if (!bag.length) {
      bag = shuffled(tools);
      if (bag[0] === need) [bag[0], bag[1]] = [bag[1], bag[0]];
    }
    return bag.shift();
  }
  need = practice ? 1 : nextNeed();

  // Leave room for both coins if the player collects them together.
  const coinSites = shuffled([13, 87]).map(x => ({x, y: between(58,78)}));
  function landingX(arrival) {
    let bestX, bestGap = -1;
    function trySpot(x) {
      // The pot occupies the center. Two tools may use the same ground
      // position provided they do not land there at nearly the same time.
      let gap = Math.abs(x - 50) / 17;
      for (const item of items) {
        const heldTime = item.held ? elapsed - item.heldSince : 0;
        const otherArrival = item.born + item.fallMs + heldTime;
        gap = Math.min(gap, Math.max(Math.abs(item.landX - x) / 19,
          Math.abs(otherArrival - arrival) / (groundRest + 1350)));
      }
      if (gap > bestGap) {bestX = x; bestGap = gap}
      return gap >= 1;
    }
    for (let i=0;i<24;i++) if (trySpot(between(11,89))) return bestX;
    for (const x of [12,88,32,68,21,79]) if (trySpot(x)) return bestX;
    return bestX;
  }
  function place(item) {
    if (item.held) return;
    const fraction = Math.min(1, Math.max(0, (elapsed - item.born) / item.fallMs));
    item.x = item.landX + (1 - fraction) * item.drift;
    const descent = reducedMotion ? fraction : fraction * (.92 + .08 * fraction);
    item.y = fraction === 1 ? item.landY : -9 + (item.landY + 9) * descent;
  }
  function spawn(tool, initial = false) {
    const fallMs = practice ? milliseconds(11500, 13500) : milliseconds(9500, 11500);
    // Opening materials are within reach. Later drops enter from above.
    const age = initial ? Math.round(fallMs * between(.33,.60)) : 0;
    const landX = landingX(elapsed + fallMs - age);
    const drift = reducedMotion ? 0 : between(Math.max(-20, 10-landX), Math.min(20,90-landX));
    const item = {
      id: 'tool-' + (++serial), tool, held: false, heldSince: 0, born: elapsed - age,
      fallMs, lifetime: fallMs + groundRest, drift, landX, landY: groundY
    };
    place(item);
    items.push(item);
  }
  function updateItems() {
    for (let i = items.length - 1; i >= 0; i--) {
      const item = items[i];
      if (!item.held && elapsed >= item.born + item.lifetime) items.splice(i, 1);
      else place(item);
    }
  }
  function rain() {
    if (items.length >= maxMaterials) return;
    const counts = tools.map(tool => items.filter(item => item.tool === tool).length);
    const fewest = Math.min(...counts);
    const tool = counts[need] === 0 ? need : choose(tools.filter(id => counts[id] === fewest));
    spawn(tool);
  }
  spawn(need, true);
  if (!practice) {
    const distractors = shuffled(tools.filter(tool => tool !== need));
    spawn(distractors[0], true);
    spawn(distractors[1], true);
  }

  // A new request becomes available every four seconds of foreground play.
  // Unused requests accumulate. Time releases requests, never growth.
  function accepting() {return !done && (practice || elapsed >= deliveries * requestInterval)}
  function advance(dtMs) {
    if (done || !Number.isFinite(dtMs) || dtMs <= 0) return;
    const until = elapsed + dtMs;
    while (elapsed < until) {
      const nextCoin = practice ? Infinity : coinTimes[minted] ?? Infinity;
      const requested = items.find(item => item.tool === need && !item.held);
      const requestExpiry = requested ? requested.born + requested.lifetime : Infinity;
      elapsed = Math.min(until, nextDrop, nextCoin, requestExpiry);
      updateItems();
      if (!practice && elapsed === nextCoin) {
        coins.push({id: 'coin-' + (minted+1), ...coinSites[minted]});
        minted++;
      }
      if (elapsed === nextDrop || elapsed === requestExpiry) {
        rain();
        nextDrop = elapsed + milliseconds(3300, 4000);
      }
    }
  }
  function hold(id) {
    const item = items.find(entry => entry.id === id);
    if (done || !item || item.held || items.some(entry => entry.held)) return false;
    item.held = true;
    item.heldSince = elapsed;
    return true;
  }
  function release(id) {
    const item = items.find(entry => entry.id === id);
    if (!item || !item.held) return false;
    item.born += elapsed - item.heldSince;
    item.held = false;
    return true;
  }
  function give(id) {
    const index = items.findIndex(item => item.id === id);
    if (done || index < 0) return null;
    const tool = items[index].tool;
    if (!accepting()) {
      release(id);
      return {waiting: true};
    }
    if (tool !== need) {
      release(id);
      return {correct: false, tool};
    }
    items.splice(index, 1);
    deliveries++;
    lastFedTool = tool;
    lastFedAt = elapsed;
    done = practice || deliveries === portions;
    if (!done) {
      need = nextNeed();
      if (!items.some(item => item.tool === need)) nextDrop = Math.min(nextDrop, elapsed + 1200);
    }
    return {correct: true, tool};
  }
  function collect(id) {
    const index = coins.findIndex(coin => coin.id === id);
    // Coins already on the ground remain collectable during the bloom reveal.
    if (index < 0) return false;
    coins.splice(index, 1);
    collected++;
    return true;
  }
  return {
    practice, items, coins, advance, hold, release, give, collect,
    get need() {return accepting() ? need : null}, get elapsed() {return elapsed}, get done() {return done},
    get growth() {return 100 * deliveries / (practice ? 1 : portions)},
    get lastFedTool() {return lastFedTool}, get justFed() {return elapsed - lastFedAt < 550},
    get deliveries() {return deliveries}, get collected() {return collected}
  };
};
