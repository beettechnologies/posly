variable "env" {
  description = "Environment name (dev | stage | prod)"
  type        = string
}

variable "tags" {
  description = "Common tags applied to all resources"
  type        = map(string)
  default     = {}
}
