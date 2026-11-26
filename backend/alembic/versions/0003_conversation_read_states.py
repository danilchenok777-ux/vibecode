"""Persist the last message each conversation member has read.

Revision ID: 0003_conversation_read_states
"""
from alembic import op
import sqlalchemy as sa

revision = "0003_conversation_read_states"
down_revision = "0002_auth_sessions"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "conversation_read_states",
        sa.Column(
            "conversation_id",
            sa.Integer(),
            sa.ForeignKey("conversations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Integer(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "last_read_message_id",
            sa.Integer(),
            sa.ForeignKey("messages.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint(
            "conversation_id",
            "user_id",
            name="pk_conversation_read_states",
        ),
    )
    op.create_index(
        "ix_conversation_read_states_user_id",
        "conversation_read_states",
        ["user_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_conversation_read_states_user_id",
        table_name="conversation_read_states",
    )
    op.drop_table("conversation_read_states")
