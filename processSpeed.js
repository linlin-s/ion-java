const originalSpeed = process.argv[2];
const ion = require('ion-js');
const fs = require('fs');
const data = fs.readFileSync("./benchmarkResult.ion")
const ionValues = ion.load(data);
const result = ionValues[0];
const newSpeed = result.get("primaryMetric").get("score");
const speed = JSON.parse(originalSpeed);
speed.push(parseFloat(newSpeed.toString()));
console.log(speed)
