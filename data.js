const ion = require('ion-js');
const fs = require('fs');
const data = fs.readFileSync("./benchmarkResult.ion")
const ionValues = ion.load(data);
const result = ionValues[0];
let newSpeed = result.get("primaryMetric").get("score");
let newHeapUsage = result.get("secondaryMetrics").get("Heap usage").get("score");
let newSerializedSize = result.get("secondaryMetrics").get("Serialized size").get("score")
let newGCAllocateRate = result.get("secondaryMetrics").get("·gc.alloc.rate").get("score")

const commitID = ["a","c","d","e"]
const speed = [3,4,5];
const heapUsage = [4,5,6]
const serializedSize = [5,6,7]
const gcAllocateRate = [3,6,7]
speed.push(parseFloat(newSpeed.toString()));
heapUsage.push(parseFloat(newHeapUsage.toString()));
serializedSize.push(parseFloat(newSerializedSize.toString()));
gcAllocateRate.push(parseFloat(newGCAllocateRate.toString()));
console.log(speed)
console.log(heapUsage)
console.log(serializedSize)
console.log(gcAllocateRate)


new Chart("speed", {
  type: "line",
  data: {
    labels: commitID,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: speed
    }]
  },
  options: {
    legend: {display: false},
  }
});

new Chart("gc.allocate.rate", {
  type: "line",
  data: {
    labels: commitID,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: gcAllocateRate
    }]
  },
  options: {
    legend: {display: false},
  }
});

new Chart("serialized_size", {
  type: "line",
  data: {
    labels: commitID,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: serializedSize
    }]
  },
  options: {
    legend: {display: false},
  }
});

new Chart("heap_usage", {
  type: "line",
  data: {
    labels: commitID,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: heapUsage
    }]
  },
  options: {
    legend: {display: false},
  }
});
