output "url" {
  description = "Address of the application."
  value       = var.enable_https ? "https://${local.host}" : "http://${aws_eip.edge.public_ip}"
}

output "edge_public_ip" {
  description = "SSH address of the edge instance (EDGE_HOST for deploy.sh and for the pipeline)."
  value       = aws_eip.edge.public_ip
}

output "data_private_ip" {
  value = local.data_private_ip
}

output "instance_ids" {
  value = { edge = aws_instance.edge.id, data = aws_instance.data.id }
}

output "ssh_command" {
  value = "ssh ubuntu@${aws_eip.edge.public_ip}"
}
