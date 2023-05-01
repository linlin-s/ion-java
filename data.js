var xValues = [1,2,3];
var yValues = [4,5,6];
var new_element = 92dfad731e4a11297f37b79309d9aa62805dd4da
var new_xValues = xValues.push(new_element);
var new_yValues = yValues.push(new_element);

new Chart("myChart1", {
  type: "line",
  data: {
    labels: new_xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: new_yValues
    }]
  },
  options: {
    legend: {display: false},
    scales: {
      yAxes: [{ticks: {min: 6, max:16}}],
    }
  }
});
new Chart("myChart2", {
  type: "line",
  data: {
    labels: new_xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: new_yValues
    }]
  },
  options: {
    legend: {display: false},
    scales: {
      yAxes: [{ticks: {min: 6, max:16}}],
    }
  }
});
