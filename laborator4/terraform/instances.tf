# Ubuntu 24.04 LTS (x86_64) published by Canonical
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

# a fixed public address that survives stop/start (billed hourly, also while the instance is stopped)
resource "aws_eip" "edge" {
  domain = "vpc"
  tags   = { Name = "${local.name}-edge" }
}

locals {
  host = var.domain_name != "" ? var.domain_name : "${replace(aws_eip.edge.public_ip, ".", "-")}.sslip.io"
  # what Caddy serves: a domain name switches on automatic HTTPS, ":80" is plain HTTP
  site_address = var.enable_https ? local.host : ":80"

  cloud_init = {
    for role in ["edge", "data"] : role => templatefile("${path.module}/cloud-init.yaml.tftpl", {
      role               = role
      ssh_public_keys    = var.ssh_public_keys
      edge_private_ip    = local.edge_private_ip
      data_private_ip    = local.data_private_ip
      site_address       = local.site_address
      private_cidr       = local.private_cidr
      vpc_cidr           = local.vpc_cidr
      nat_script         = base64encode(file("${path.module}/nat.sh"))
      auto_stop_time     = var.auto_stop_time
      auto_stop_timezone = var.auto_stop_timezone
    })
  }
}

resource "aws_instance" "edge" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.edge_instance_type
  subnet_id                   = aws_subnet.public.id
  private_ip                  = local.edge_private_ip
  vpc_security_group_ids      = [aws_security_group.edge.id]
  associate_public_ip_address = true  # needed during the first boot, then replaced by the Elastic IP
  source_dest_check           = false # forwards the traffic of the private subnet (NAT)

  # `shutdown` inside the instance (auto-stop) must stop it, not delete it
  instance_initiated_shutdown_behavior = "stop"

  # runs once, at the first boot. To apply a change: terraform apply -replace=aws_instance.edge
  user_data = local.cloud_init["edge"]

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.volume_size_gb
    encrypted   = true
  }

  dynamic "credit_specification" {
    for_each = startswith(var.edge_instance_type, "t") ? [1] : []
    content {
      cpu_credits = var.cpu_credits
    }
  }

  tags = { Name = "${local.name}-edge", Role = "edge" } # deploy/vm.sh finds the instances by these tags

  lifecycle {
    # a newer Ubuntu image must not silently replace a running instance
    ignore_changes = [ami, associate_public_ip_address]
  }

  depends_on = [aws_route.public_internet, aws_route_table_association.public]
}

resource "aws_eip_association" "edge" {
  instance_id   = aws_instance.edge.id
  allocation_id = aws_eip.edge.id
}

resource "aws_instance" "data" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.data_instance_type
  subnet_id                   = aws_subnet.private.id
  private_ip                  = local.data_private_ip
  vpc_security_group_ids      = [aws_security_group.data.id]
  associate_public_ip_address = false

  instance_initiated_shutdown_behavior = "stop"

  # runs once, at the first boot. To apply a change: terraform apply -replace=aws_instance.data (deletes the data)
  user_data = local.cloud_init["data"]

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.volume_size_gb
    encrypted   = true
  }

  dynamic "credit_specification" {
    for_each = startswith(var.data_instance_type, "t") ? [1] : []
    content {
      cpu_credits = var.cpu_credits
    }
  }

  tags = { Name = "${local.name}-data", Role = "data" }

  lifecycle {
    ignore_changes = [ami]
  }

  # its first boot installs packages through the NAT of the edge instance
  depends_on = [
    aws_route.private_nat,
    aws_route_table_association.private,
    aws_eip_association.edge,
    aws_vpc_security_group_ingress_rule.edge_web,
    aws_vpc_security_group_egress_rule.edge_all,
    aws_vpc_security_group_egress_rule.data_all,
  ]
}
