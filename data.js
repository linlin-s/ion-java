var yValues = [1,2,3];
var yValues = [$(echo "[$(echo "[$(echo "[$(echo "[$(echo "[$(echo "[$(echo "[\1, $a]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh), 7]" | jq -s flatten | jq -r @sh)];

new Chart("myChart1", {
  type: "line",
  data: {
    labels: xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: yValues
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
    labels: xValues,
    datasets: [{
      fill: false,
      lineTension: 0,
      backgroundColor: "rgba(0,0,255,1.0)",
      borderColor: "rgba(0,0,255,0.1)",
      data: yValues
    }]
  },
  options: {
    legend: {display: false},
    scales: {
      yAxes: [{ticks: {min: 6, max:16}}],
    }
  }
});
