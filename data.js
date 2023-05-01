var xValues = [1,2,3];
var yValues = [4,5,6];
var new_element = 7544a13afb5d5b837b2a64a89f8239e3a5ab7338
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
