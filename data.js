const speed = [0.874,1.421,0.989,1.022,0.986];
const heapUsage = [3.474,3.469,3.470,3.470,3.466];
const serializedSize = [0.053,0.053,0.053,0.053,0.052]
const gcAllocateRate = [0.196,0.196,0.196,0.196,0.196]
const commitID = [“2791c29”,“d23e023”, “fb0b2c9”,“e24d7fb”, “133664d”]




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
