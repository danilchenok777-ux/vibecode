"""Persist per-recipient message delivery receipts.

Revision ID: 0005_message_delivery_states
"""
from alembic import op
import sqlalchemy as sa

revision = "0005_message_delivery_states"
down_revision = "0004_attachments"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "message_delivery_states",
        sa.Column(
            "message_id",
            sa.Integer(),
            sa.ForeignKey("messages.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "user_id",
            sa.Integer(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "delivered_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_table("message_delivery_states")
