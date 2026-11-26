#!/usr/bin/env bash
# Start / stop / show both EC2 instances (found by their tags). A stopped instance is not billed for
# compute; its disk and the Elastic IP still are.
#   ./deploy/vm.sh start|stop|status           (region: AWS_REGION, default eu-central-1)
set -euo pipefail
action=${1:-status}
region=${AWS_REGION:-eu-central-1}

aws_() { aws --region "$region" "$@"; }
filters() { echo "Name=tag:Project,Values=pad-lab4" "Name=instance-state-name,Values=pending,running,stopping,stopped"; }
id_of() {
  local id
  # shellcheck disable=SC2046
  id=$(aws_ ec2 describe-instances --filters $(filters) "Name=tag:Role,Values=$1" \
         --query 'Reservations[].Instances[].InstanceId' --output text)
  [ -n "$id" ] || { echo "no '$1' instance in $region (terraform apply first?)" >&2; return 1; }
  echo "$id"
}

case $action in
  start)   # edge first: the private instance reaches the internet through it
    edge=$(id_of edge); data=$(id_of data)
    aws_ ec2 start-instances --instance-ids "$edge" >/dev/null
    aws_ ec2 wait instance-running --instance-ids "$edge"
    aws_ ec2 start-instances --instance-ids "$data" >/dev/null
    aws_ ec2 wait instance-running --instance-ids "$data"
    ;;
  stop)
    edge=$(id_of edge); data=$(id_of data)
    aws_ ec2 stop-instances --instance-ids "$data" "$edge" >/dev/null
    aws_ ec2 wait instance-stopped --instance-ids "$data" "$edge"
    ;;
  status) ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
# shellcheck disable=SC2046
aws_ ec2 describe-instances --filters $(filters) --output table \
  --query "Reservations[].Instances[].{name:Tags[?Key=='Name']|[0].Value,state:State.Name,type:InstanceType,privateIp:PrivateIpAddress,publicIp:PublicIpAddress}"
