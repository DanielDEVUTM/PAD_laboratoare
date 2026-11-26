# Security groups: everything that comes in and is not listed here is refused.

resource "aws_security_group" "edge" {
  name        = "${local.name}-edge"
  description = "Public instance: reverse proxy, gateway, NAT"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-edge" }
}

resource "aws_security_group" "data" {
  name        = "${local.name}-data"
  description = "Private instance: reachable only from the edge instance"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-data" }
}

# ---------------------------------------------------------------- edge

# also lets in what the private instance sends out through the NAT (HTTP and HTTPS)
resource "aws_vpc_security_group_ingress_rule" "edge_web" {
  for_each          = toset(["80", "443"])
  security_group_id = aws_security_group.edge.id
  description       = "Web"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
}

resource "aws_vpc_security_group_ingress_rule" "edge_ssh" {
  for_each          = toset(var.ssh_source_cidrs)
  security_group_id = aws_security_group.edge.id
  description       = "SSH (keys only)"
  cidr_ipv4         = each.key
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
}

resource "aws_vpc_security_group_ingress_rule" "edge_gateway" {
  security_group_id            = aws_security_group.edge.id
  description                  = "Warehouse nodes register in the gateway"
  referenced_security_group_id = aws_security_group.data.id
  ip_protocol                  = "tcp"
  from_port                    = 8000
  to_port                      = 8000
}

resource "aws_vpc_security_group_egress_rule" "edge_all" {
  security_group_id = aws_security_group.edge.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ---------------------------------------------------------------- data

resource "aws_vpc_security_group_ingress_rule" "data_from_edge" {
  for_each = {
    ssh          = { from = 22, to = 22 }
    orchestrator = { from = 8001, to = 8001 }
    sync         = { from = 8002, to = 8002 }
    apis         = { from = 8101, to = 8199 }
  }
  security_group_id            = aws_security_group.data.id
  description                  = each.key
  referenced_security_group_id = aws_security_group.edge.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.from
  to_port                      = each.value.to
}

resource "aws_vpc_security_group_egress_rule" "data_all" {
  security_group_id = aws_security_group.data.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
