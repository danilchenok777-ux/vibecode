"""Persist private conversation attachment metadata.

Revision ID: 0004_attachments
"""
from alembic import op
import sqlalchemy as sa

revision = "0004_attachments"
down_revision = "0003_conversation_read_states"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "attachments",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "message_id",
            sa.Integer(),
            sa.ForeignKey("messages.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("object_key", sa.String(length=512), nullable=False),
        sa.Column("filename", sa.String(length=255), nullable=False),
        sa.Column("content_type", sa.String(length=128), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.UniqueConstraint("message_id", name="uq_attachments_message_id"),
        sa.UniqueConstraint("object_key", name="uq_attachments_object_key"),
    )
    op.create_index("ix_attachments_created_at", "attachments", ["created_at"])


def downgrade() -> None:
    op.drop_index("ix_attachments_created_at", table_name="attachments")
    op.drop_table("attachments")
