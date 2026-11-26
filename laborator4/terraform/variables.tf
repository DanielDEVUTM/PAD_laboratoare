variable "region" {
  type    = string
  default = "eu-central-1"
}

variable "availability_zone" {
  description = "Zone of both subnets; empty = the first zone of the region."
  type        = string
  default     = ""
}

variable "edge_instance_type" {
  description = "Public instance: reverse proxy, UI, gateway, Redis (also the NAT for the private one)."
  type        = string
  default     = "t3.micro" # 2 vCPU, 1 GiB, listed as Free Tier eligible
}

variable "data_instance_type" {
  description = "Private instance: orchestrator, sync, warehouses. t3.small = 1 warehouse; c7i-flex.large = 2-3."
  type        = string
  default     = "t3.small" # 2 vCPU, 2 GiB, listed as Free Tier eligible
}

variable "cpu_credits" {
  description = "Only for t* instances. \"unlimited\" can add a small charge under long heavy load; \"standard\" is slow after a start."
  type        = string
  default     = "unlimited"

  validation {
    condition     = contains(["standard", "unlimited"], var.cpu_credits)
    error_message = "cpu_credits must be \"standard\" or \"unlimited\"."
  }
}

variable "volume_size_gb" {
  description = "Root disk of each instance (gp3)."
  type        = number
  default     = 20
}

variable "ssh_public_keys" {
  description = "Public keys allowed to sign in: yours and the one used by the CI/CD pipeline."
  type        = list(string)

  validation {
    condition     = length(var.ssh_public_keys) > 0 && alltrue([for k in var.ssh_public_keys : can(regex("^(ssh-ed25519|ssh-rsa) ", k))])
    error_message = "Give at least one public key (ssh-ed25519 ... or ssh-rsa ...)."
  }
}

variable "ssh_source_cidrs" {
  description = "Who may reach SSH on the edge instance. GitHub-hosted runners have changing addresses, so the pipeline needs 0.0.0.0/0."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enable_https" {
  description = "true = HTTPS with a Let's Encrypt certificate obtained by the proxy; false = plain HTTP (the password travels unencrypted)."
  type        = bool
  default     = true
}

variable "domain_name" {
  description = "Your own domain, with an A record to the public IP. Empty = <ip>.sslip.io (Let's Encrypt refuses *.amazonaws.com names)."
  type        = string
  default     = ""
}

variable "auto_stop_time" {
  description = "Both instances stop themselves every day at this time (HH:MM)."
  type        = string
  default     = "23:00"

  validation {
    condition     = can(regex("^([01][0-9]|2[0-3]):[0-5][0-9]$", var.auto_stop_time))
    error_message = "auto_stop_time must be HH:MM, e.g. 23:00."
  }
}

variable "auto_stop_timezone" {
  type    = string
  default = "Europe/Chisinau"
}
