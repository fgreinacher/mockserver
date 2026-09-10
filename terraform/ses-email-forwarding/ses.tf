# ──────────────────────────────────────────────────────────────────────────────
# SES Domain Identity + DKIM
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_domain_identity" "domain" {
  domain = var.domain
}

resource "aws_route53_record" "ses_verification" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "_amazonses.${var.domain}"
  type    = "TXT"
  ttl     = 600
  records = [aws_ses_domain_identity.domain.verification_token]
}

resource "aws_ses_domain_dkim" "domain" {
  domain = aws_ses_domain_identity.domain.domain
}

# SES Easy DKIM always returns exactly three CNAME tokens. `count = 3` keeps the
# instance count static (known at plan time) — a `for_each` over dkim_tokens fails
# to plan because the token values are only known after apply.
resource "aws_route53_record" "ses_dkim" {
  count = 3

  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "${aws_ses_domain_dkim.domain.dkim_tokens[count.index]}._domainkey.${var.domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.domain.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# ──────────────────────────────────────────────────────────────────────────────
# MX Record — route inbound email to SES
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_route53_record" "mx" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = var.domain
  type    = "MX"
  ttl     = 600
  records = ["10 inbound-smtp.${var.region}.amazonaws.com"]
}

# ──────────────────────────────────────────────────────────────────────────────
# DMARC Record
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_route53_record" "dmarc" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 600
  # Audit finding F-WEB-11: was `p=none` (report-only). Moving to `p=quarantine`
  # instructs receivers to send unaligned mail to spam. Add `rua=` to receive
  # aggregate reports — monitor for a week before considering `p=reject`.
  records = ["v=DMARC1; p=quarantine; rua=mailto:dmarc-reports@${var.domain}; ruf=mailto:dmarc-reports@${var.domain}; fo=1;"]
}

# ──────────────────────────────────────────────────────────────────────────────
# ADDITIONAL DOMAINS
#
# ⚠️  DELIBERATELY ADDITIVE, NOT A `for_each` CONVERSION OF THE BLOCKS ABOVE.
#     Turning `aws_ses_domain_identity.domain` into `...domain["mock-server.com"]`
#     changes its Terraform address, and without a `moved` block Terraform would
#     DESTROY AND RECREATE the live identity, its three DKIM CNAMEs and the MX —
#     i.e. break inbound mail and force DKIM re-verification, which takes up to 72
#     hours to propagate. `moved` blocks can express that safely, but they are a
#     one-shot migration that has to be exactly right, and getting it wrong is
#     discovered by mail bouncing rather than by a plan diff.
#     The cost of this choice is five duplicated resource blocks. The benefit is
#     that mock-server.com's addresses do not move, so THIS CHANGE CANNOT RECREATE
#     ANYTHING THAT ALREADY EXISTS. If the duplication ever becomes a real problem,
#     do the `for_each` migration on its own, with `moved` blocks, and nothing else
#     in the same apply.
#
# ⚠️  WHY THERE IS STILL ONLY ONE RULE SET. `aws_ses_active_receipt_rule_set`
#     activates one rule set and DEACTIVATES any other in the same account and
#     region. A second stack for a second domain would silently switch this one
#     off. SES receipt rules take a LIST of recipients, so one rule serves every
#     domain here — see `recipients` below.
# ──────────────────────────────────────────────────────────────────────────────

data "aws_route53_zone" "additional" {
  for_each = toset(var.additional_domains)
  name     = each.value
}

resource "aws_ses_domain_identity" "additional" {
  for_each = toset(var.additional_domains)
  domain   = each.value
}

resource "aws_route53_record" "additional_ses_verification" {
  for_each = toset(var.additional_domains)

  zone_id = data.aws_route53_zone.additional[each.key].zone_id
  name    = "_amazonses.${each.value}"
  type    = "TXT"
  ttl     = 600
  records = [aws_ses_domain_identity.additional[each.key].verification_token]
}

resource "aws_ses_domain_dkim" "additional" {
  for_each = toset(var.additional_domains)
  domain   = aws_ses_domain_identity.additional[each.key].domain
}

# Three CNAMEs per domain. `count` cannot be used inside `for_each`, so the pairs
# are built explicitly; the token VALUES are only known after apply, which is why
# the index — not the token — is the map key.
resource "aws_route53_record" "additional_ses_dkim" {
  for_each = {
    for pair in setproduct(var.additional_domains, [0, 1, 2]) :
    "${pair[0]}-${pair[1]}" => { domain = pair[0], idx = pair[1] }
  }

  zone_id = data.aws_route53_zone.additional[each.value.domain].zone_id
  name    = "${aws_ses_domain_dkim.additional[each.value.domain].dkim_tokens[each.value.idx]}._domainkey.${each.value.domain}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.additional[each.value.domain].dkim_tokens[each.value.idx]}.dkim.amazonses.com"]
}

resource "aws_route53_record" "additional_mx" {
  for_each = toset(var.additional_domains)

  zone_id = data.aws_route53_zone.additional[each.key].zone_id
  name    = each.value
  type    = "MX"
  ttl     = 600
  records = ["10 inbound-smtp.${var.region}.amazonaws.com"]
}

resource "aws_route53_record" "additional_dmarc" {
  for_each = toset(var.additional_domains)

  zone_id = data.aws_route53_zone.additional[each.key].zone_id
  name    = "_dmarc.${each.value}"
  type    = "TXT"
  ttl     = 600
  records = ["v=DMARC1; p=quarantine; rua=mailto:dmarc-reports@${each.value}; ruf=mailto:dmarc-reports@${each.value}; fo=1;"]
}

# ──────────────────────────────────────────────────────────────────────────────
# Verify destination addresses (required while SES is in sandbox mode)
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_email_identity" "forward_to" {
  for_each = toset(var.forward_to)
  email    = each.value
}

# ──────────────────────────────────────────────────────────────────────────────
# SES Receipt Rule — catch-all: write to S3 then invoke Lambda
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_ses_receipt_rule_set" "main" {
  rule_set_name = "${replace(var.domain, ".", "-")}-inbound"
}

resource "aws_ses_active_receipt_rule_set" "main" {
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
}

resource "aws_ses_receipt_rule" "forward" {
  name          = "${replace(var.domain, ".", "-")}-forward"
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
  # ⚠️ ONE RULE, EVERY DOMAIN. A bare domain here matches every address at it, so
  #     this is the catch-all for all of them. Adding a domain to
  #     var.additional_domains is enough — do NOT add a second rule set.
  recipients   = concat([var.domain], var.additional_domains)
  enabled      = true
  scan_enabled = true
  # Audit finding F-WEB-10: previously "Optional" (default). Setting to
  # "Require" enforces TLS for inbound SMTP connections; senders that don't
  # support STARTTLS will be rejected at the gateway.
  tls_policy = "Require"

  s3_action {
    position          = 1
    bucket_name       = aws_s3_bucket.mail.id
    object_key_prefix = "incoming/"
  }

  lambda_action {
    position        = 2
    function_arn    = aws_lambda_function.forwarder.arn
    invocation_type = "Event"
  }

  depends_on = [
    aws_s3_bucket_policy.mail,
    aws_lambda_permission.ses,
  ]
}
