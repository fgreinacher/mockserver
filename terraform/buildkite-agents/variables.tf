variable "buildkite_agent_token" {
  description = <<-EOT
    Buildkite agent registration token.

    NEVER write this value to terraform.tfvars. Supply it at apply time via
    an environment variable:

      export TF_VAR_buildkite_agent_token=$(aws ssm get-parameter \
        --name /buildkite/buildkite/agent-token \
        --with-decryption --query Parameter.Value --output text \
        --profile mockserver-build)

    The run.sh wrapper does this automatically.
  EOT
  type        = string
  sensitive   = true
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-west-2"
}

variable "instance_types" {
  # 8 vCPU / 32 GiB (m5-class), NOT c5.2xlarge (8 vCPU / 16 GiB). The `:maven:
  # build` and `:nexus: deploy snapshot` steps run one container (agents_per_instance
  # = 1) that holds BOTH a 6g-Xmx Maven reactor JVM (mockserver/.mvn/jvm.config,
  # driven to its ceiling by `-T 1C`) AND, in the same cgroup, the dashboard
  # `vite build` spawned by mockserver-netty's frontend-maven-plugin — whose
  # ~1.5-3g peak is rolldown NATIVE memory that no Node heap flag can bound. On
  # 16 GiB those two could not both fit under the 7g container limit and OOM-killed
  # (exit 137) ~half of master's builds. The container limit is raised to 12g
  # (.buildkite/scripts/steps/java-build.sh, java-deploy-snapshot.sh); a 12g
  # container needs a >=32 GiB host to leave the daemon/agent/OS room, so every
  # type here is a same-vCPU 32 GiB variant (keeps the 8-vCPU assumption the perf
  # gates rely on). Keep them ALL 8 vCPU / 32 GiB when editing for Spot diversity.
  description = "EC2 instance types (comma-separated), all 8 vCPU / 32 GiB. First type preferred for on-demand."
  type        = string
  default     = "m5.2xlarge"
}

variable "min_size" {
  description = "Minimum number of agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "max_size" {
  description = "Maximum number of agent instances"
  type        = number
  default     = 10
}

variable "on_demand_percentage" {
  description = "Percentage of on-demand instances (0 = all spot, 100 = all on-demand)"
  type        = number
  default     = 0
}

variable "release_min_size" {
  description = "Minimum number of release agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "release_max_size" {
  description = "Maximum number of release agent instances (release queue)"
  type        = number
  default     = 2
}

variable "trigger_instance_types" {
  description = "EC2 instance types for trigger queue (cheap, low-CPU — only runs curl/sleep polling loops)"
  type        = string
  default     = "t3.small"
}

variable "trigger_min_size" {
  description = "Minimum number of trigger agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "trigger_max_size" {
  description = "Maximum number of trigger agent instances"
  type        = number
  default     = 4
}

variable "perf_instance_types" {
  description = "EC2 instance type for the perf queue — a SINGLE fixed-performance type (no comma list) for reproducible benchmark numbers"
  type        = string
  default     = "c5.4xlarge"
}

variable "perf_min_size" {
  description = "Minimum perf agent instances. MUST be 0 (scale to zero — zero idle cost; AGENTS.md hard constraint)"
  type        = number
  default     = 0
}

variable "perf_max_size" {
  description = "Maximum perf agent instances (1 — never run two perf jobs concurrently so they don't contend)"
  type        = number
  default     = 1
}

variable "alert_email" {
  description = "Email address for infrastructure alerts (SNS notifications)"
  type        = string
  default     = ""
}
