import json

from flask import g, jsonify

from .. import get_db
from ..auth import authed


def register_routes(bp):
    @bp.route("/lists", methods=["GET"])
    @authed
    def get_lists_view():
        conn = get_db()
        rows = conn.execute(
            "SELECT lists.id, lists.name, lists.category_order "
            "FROM lists JOIN memberships ON memberships.list_id = lists.id "
            "WHERE memberships.account_id = ? AND lists.deleted = 0 "
            "ORDER BY lists.name",
            (g.account.id,),
        ).fetchall()
        lists = [
            {
                "id": row["id"],
                "name": row["name"],
                "category_order": json.loads(row["category_order"]),
            }
            for row in rows
        ]
        return jsonify({"lists": lists}), 200
