#!/bin/sh
# Builds the images and deploys the whole lab on the current Kubernetes cluster.
#   Docker Desktop (Kubernetes enabled): images are visible to the cluster as they are.
#   kind:      kind load docker-image lab3-gateway lab3-dw-node lab3-ui
#   minikube:  minikube image load lab3-gateway lab3-dw-node lab3-ui
set -e
cd "$(dirname "$0")/.."
docker build -t lab3-gateway ./gateway
docker build -t lab3-dw-node ./dw-node
docker build -t lab3-ui ./ui
# --load-restrictor: the ConfigMap files live in ../infra (shared with docker-compose)
kubectl kustomize k8s --load-restrictor LoadRestrictionsNone | kubectl apply -f -
echo
echo "Wait for the pods:   kubectl -n pad-lab3 get pods -w"
echo "Gateway:             http://localhost:30800   (or: kubectl -n pad-lab3 port-forward svc/gateway 8000:8000)"
echo "UI:                  http://localhost:30300   (UI talks to http://localhost:8000 -> use the port-forward above)"
