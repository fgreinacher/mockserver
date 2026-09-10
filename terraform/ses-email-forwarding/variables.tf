variable "region" {
  description = "AWS region (must support SES inbound)"
  type        = string
  default     = "us-east-1"
}

variable "domain" {
  description = "Domain to receive email for (catch-all)"
  type        = string
  default     = "mock-server.com"
}

variable "additional_domains" {
  description = "Further domains to receive catch-all email for, alongside var.domain. Each gets its own SES identity, DKIM CNAMEs, MX and DMARC records; they SHARE the rule set, receipt rule, S3 bucket, Lambda and alarm."
  type        = list(string)

  # ⚠️  THE LIVE DOMAINS ARE THE DEFAULT, AND THAT IS DELIBERATE. DO NOT EMPTY IT.
  #
  #     This value is NOT safe to pass as `-var` on the command line. Terraform does
  #     not persist a `-var`, so the next run without the same flag sees an empty
  #     list and DESTROYS every resource for the missing domain — the SES identity,
  #     its three DKIM CNAMEs, its MX and its DMARC record. Inbound mail for that
  #     domain stops, and DKIM re-verification after re-adding can take up to 72
  #     hours. The plan would say "4 to destroy" and nothing would say why.
  #
  #     `terraform.tfvars` is not a fix either: it is gitignored, so a fresh clone,
  #     another machine or CI would drop the value and destroy the same resources.
  #
  #     So the domains live here, committed, exactly as `var.domain` already carries
  #     "mock-server.com" as its default. Adding a domain means editing this list and
  #     committing it. Anyone overriding it must pass the FULL list, never a subset.
  default = ["learn-ice-hockey.com"]

  validation {
    condition     = alltrue([for x in var.additional_domains : can(regex("^[a-z0-9.-]+\\.[a-z]{2,}$", x))])
    error_message = "additional_domains must be bare domain names, e.g. \"example.com\"."
  }
}

variable "forward_to" {
  description = "List of email addresses to forward inbound mail to"
  type        = list(string)
  default     = ["jamesdbloom@gmail.com"]

  validation {
    condition     = length(var.forward_to) > 0
    error_message = "forward_to must contain at least one email address."
  }
}

variable "from_local_part" {
  description = "Local part used to build the rewritten From address per receiving domain, e.g. \"noreply\" gives noreply@<the domain the mail arrived at>. Falls back to var.from_address when the receiving domain is not one this stack verifies."
  type        = string
  default     = "noreply"
}

variable "from_address" {
  description = "Fallback verified sender for the rewritten From header, used only when the receiving domain cannot be determined"
  type        = string
  default     = "noreply@mock-server.com"

  validation {
    condition     = can(regex("^.+@.+\\..+$", var.from_address))
    error_message = "from_address must be a valid email address."
  }
}

variable "email_retention_days" {
  description = "Number of days to retain raw emails in S3 before automatic deletion"
  type        = number
  default     = 30
}

variable "alarm_email" {
  description = "Email address for Lambda error alarm notifications (SNS subscription must be confirmed once via the email AWS sends after the first apply)"
  type        = string
  default     = ""
}

variable "enable_monitoring" {
  description = "Whether to create the SNS alarm topic, email subscription, and CloudWatch error alarm. Set to false in environments where the SNS management API is unreachable (e.g. behind a corporate TLS-inspection proxy). Monitoring can be applied later from a network where SNS is reachable."
  type        = bool
  default     = true
}
