terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.74"
    }
  }
  # state is local (terraform.tfstate, ignored by git): infrastructure is applied from one computer
}

# credentials come from `aws configure` (or AWS_PROFILE / AWS_ACCESS_KEY_ID...): never write keys here
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = local.name
      ManagedBy = "terraform"
    }
  }
}
