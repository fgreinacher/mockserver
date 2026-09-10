# ──────────────────────────────────────────────────────────────────────────────
# Lambda Function — SES email forwarder
# ──────────────────────────────────────────────────────────────────────────────

data "archive_file" "forwarder" {
  type        = "zip"
  source_dir  = "${path.module}/lambda"
  output_path = "${path.module}/.terraform/tmp/forwarder.zip"
}

resource "aws_lambda_function" "forwarder" {
  function_name    = "${replace(var.domain, ".", "-")}-email-forwarder"
  filename         = data.archive_file.forwarder.output_path
  source_code_hash = data.archive_file.forwarder.output_base64sha256
  handler          = "index.handler"
  runtime          = "nodejs20.x"
  timeout          = 30
  memory_size      = 256
  role             = aws_iam_role.forwarder.arn

  environment {
    variables = {
      MAIL_BUCKET     = aws_s3_bucket.mail.id
      MAIL_KEY_PREFIX = "incoming/"
      FROM_ADDRESS    = var.from_address
      FORWARD_TO      = join(",", var.forward_to)
      # The Lambda rewrites From to <local part>@<the domain the mail arrived at>,
      # so forwarded mail for a second domain does not appear to come from the
      # first. VERIFIED_DOMAINS is the allow-list it checks the recipient against
      # before doing that — anything else falls back to FROM_ADDRESS.
      FROM_LOCAL_PART  = var.from_local_part
      VERIFIED_DOMAINS = join(",", concat([var.domain], var.additional_domains))
    }
  }

  depends_on = [aws_cloudwatch_log_group.forwarder]
}

# ──────────────────────────────────────────────────────────────────────────────
# IAM Role + Policy for the Lambda function
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_iam_role" "forwarder" {
  name = "${replace(var.domain, ".", "-")}-email-forwarder"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "lambda.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy" "forwarder" {
  name = "email-forwarder"
  role = aws_iam_role.forwarder.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadMailFromS3"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.mail.arn}/*"
      },
      {
        # Audit finding F-WEB-12: previously `Resource: "*"`. Scoped to specific
        # SES identities so a compromised Lambda cannot send as any other
        # verified identity in the account.
        #
        # The account runs in the SES sandbox (no production access). In the
        # sandbox, SES authorises `ses:SendRawEmail` against BOTH the sender
        # identity (the domain, from the rewritten From address) AND each
        # recipient identity. Scoping the resource to the domain alone therefore
        # breaks forwarding with an AccessDenied on the recipient identity
        # (e.g. identity/jamesdbloom@gmail.com). Authorise the domain plus every
        # `forward_to` recipient identity to keep least privilege while allowing
        # the send to succeed.
        Sid    = "SendForwardedEmail"
        Effect = "Allow"
        Action = "ses:SendRawEmail"
        # ⚠️ EVERY receiving domain needs its own identity ARN here, not just
        #    var.domain — the Lambda rewrites From to the domain the mail arrived
        #    at, and SES refuses a send from an identity this role cannot use.
        #    Omitting an additional domain fails at SEND time, in a Lambda invoked
        #    asynchronously, so the symptom is silently unforwarded mail plus an
        #    alarm — not a plan error.
        Resource = concat(
          [for dom in concat([var.domain], var.additional_domains) :
          "arn:aws:ses:${var.region}:${data.aws_caller_identity.current.account_id}:identity/${dom}"],
          [for addr in var.forward_to : "arn:aws:ses:${var.region}:${data.aws_caller_identity.current.account_id}:identity/${addr}"]
        )
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/lambda/${replace(var.domain, ".", "-")}-email-forwarder:*"
      }
    ]
  })
}

# ──────────────────────────────────────────────────────────────────────────────
# Lambda Permission — allow SES to invoke the function
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_lambda_permission" "ses" {
  statement_id   = "AllowSESInvoke"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.forwarder.function_name
  principal      = "ses.amazonaws.com"
  source_account = data.aws_caller_identity.current.account_id
}

# ──────────────────────────────────────────────────────────────────────────────
# CloudWatch Log Group
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "forwarder" {
  name              = "/aws/lambda/${replace(var.domain, ".", "-")}-email-forwarder"
  retention_in_days = 30
}
