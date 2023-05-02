const commitID = ["a","c","d","e"]
const speed = [1,2,4];
const heapUsage = [4,5,6]
const serializedSize = [5,6,7]
const gcAllocateRate = [3,6,7]



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
