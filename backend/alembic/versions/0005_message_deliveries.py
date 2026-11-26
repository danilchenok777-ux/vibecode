"""Persist per-recipient message delivery acknowledgements.

Revision ID: 0005_message_deliveries
"""
from alembic import op
import sqlalchemy as sa

revision = "0005_message_deliveries"
down_revision = "0004_attachments"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "message_deliveries",
        sa.Column(
            "message_id",
            sa.Integer(),
            sa.ForeignKey("messages.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Integer(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "delivered_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint(
            "message_id",
            "user_id",
            name="pk_message_deliveries",
        ),
    )
    op.create_index(
        "ix_message_deliveries_user_id",
        "message_deliveries",
        ["user_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_message_deliveries_user_id",
        table_name="message_deliveries",
    )
    op.drop_table("message_deliveries")
