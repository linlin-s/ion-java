const xValues = ["a","c","v"];
const speed = [3,4,5];
const heap_usage = [3,4,5];
const serialized_size = [3,4,5];
const gc = [3,4,5];
new Chart("speed", {
  type: "line",
  data: {
    labels: xValues,
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
    labels: xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: heap_usage
    }]
  },
  options: {
    legend: {display: false},
  }
});

new Chart("serialized_size", {
  type: "line",
  data: {
    labels: xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: serialized_size
    }]
  },
  options: {
    legend: {display: false},
  }
});

new Chart("heap_usage", {
  type: "line",
  data: {
    labels: xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: heap_usage
    }]
  },
  options: {
    legend: {display: false},
  }
});