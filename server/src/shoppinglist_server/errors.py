class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, details: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        # Optional extra keys merged into the JSON error envelope (never overriding
        # error/message). Used by /sync validation to name the offending row_id + field so a
        # client can quarantine just that row instead of blocking its whole push queue.
        self.details = details
